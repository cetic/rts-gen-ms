package be.cetic.tsimulus.ws

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.headers.HttpOrigin
import akka.http.scaladsl.model.headers.HttpOriginRange
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers
import akka.http.scaladsl.server.ExceptionHandler
import akka.http.scaladsl.server.RejectionHandler
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.http.scaladsl.server.directives.ExecutionDirectives._
import akka.util.ByteString
import be.cetic.tsimulus.Utils
import be.cetic.tsimulus.config.Configuration
import com.github.nscala_time.time.Imports._
import org.joda.time.format.{DateTimeFormat, DateTimeFormatterBuilder}
import spray.json._
import ch.megard.akka.http.cors.scaladsl._
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import ch.megard.akka.http.cors.scaladsl.model.HttpHeaderRange
import scala.io.StdIn
import scala.collection.immutable.Seq


/**
 * /generator => All the values of a call to the generator with a configuration document provided in the POST parameter
 * /generator/date => The values of all data for the greatest date before or equal to the specified one. format: yyyy-MM-dd'T'HH:mm:ss.SSS
 * /generator/d1/d2 => The values for the dates between d1 (excluded) and d2 (included)
 */
object GeneratorWebServer {

	private val dtf = DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss.SSS")

			val datetimeFormatter = {
					val parsers = Array(
							DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss.SSS").getParser,
							DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss").getParser
							)

							new DateTimeFormatterBuilder().append(null, parsers).toFormatter()
	}

	def main(args: Array[String]) : Unit =
		{

				val parser = new scopt.OptionParser[Config]("ts-gen-service") {
					head("ts-gen-service", "0.0.1")

					opt[Int]('p', "port").action( (x, c) =>
					c.copy(port = x) )
					.text("The port the service must listen to.")

					opt[String]('h', "host")
					.action( (x, c) => c.copy(host = x) )
					.text("The host on which the service is running.")
				}

				if(parser.parse(args, Config()).isEmpty) System.exit(1)
				val config = parser.parse(args, Config()).get

				implicit val system = ActorSystem("tsgen-system")
				implicit val materializer = ActorMaterializer()
				implicit val executionContext = system.dispatcher

				//val route =  lastRoute ~ fullRoute

				val bindingFuture = Http().bindAndHandle(route, config.host, config.port)

				println(s"Server online at http://${config.host}:${config.port}/\nPress RETURN to stop...")
				StdIn.readLine() // let it run until user presses return
				bindingFuture
				.flatMap(_.unbind()) // trigger unbinding from the port
				.onComplete(_ => system.terminate()) // and shutdown when done
		}
	def route: Route = {
			import CorsDirectives._
			import Directives._

			// CORS settings
			val corsSettings = CorsSettings.defaultSettings.copy(
					allowedOrigins = HttpOriginRange(HttpOrigin(Cors().origin))
					)

			// rejection handler
			val rejectionHandler = corsRejectionHandler withFallback RejectionHandler.default

			// exception handler
			val exceptionHandler = ExceptionHandler {
			case e: NoSuchElementException => complete(StatusCodes.NotFound -> e.getMessage)
			}

			val handleErrors = handleRejections(rejectionHandler) & handleExceptions(exceptionHandler)

					// Note how rejections and exceptions are handled *before* the CORS directive (in the inner route).
					// This is required to have the correct CORS headers in the response even when an error occurs.
					handleErrors {
				cors(corsSettings) {
					handleErrors {
						val fullRoute = path("generator")
								{
							post
							{
								decodeRequest
								{
									entity(as[String])
									{ document =>
									  
									val config = Configuration(document.parseJson)

									val results = Utils.generate(Utils.config2Results(config))

									val answer = Source(results.map(x => dtf.print(x._1) + ";" + x._2 + ";" + x._3))

									complete(
											HttpEntity(
													ContentTypes.`text/csv(UTF-8)`,
													answer.map(a => ByteString(s"$a\n"))
													)
											)
									}
								}
							}
								}


						val lastRoute = path("generator" / PathMatchers.Segments)
								{
							segments =>
							post
							{
								decodeRequest
								{
									entity(as[String])
									{ document =>
									  
									val config = Configuration(document.parseJson)

									val answer = segments match {

									case List(limit) => {
										// We are looking for the values corresponding to a particular date
										val reference = datetimeFormatter.parseLocalDateTime(limit)
												val last = scala.collection.mutable.Map[String, (LocalDateTime, String)]()
												val results = Utils.eval(config, reference)

												Source(results.map(entry => dtf.print(reference) + ";" + entry._1 + ";" + entry._2.getOrElse("NA").toString))
									}

									case List(start, stop) => {

										val results = Utils.generate(Utils.config2Results(config))

												val startDate = datetimeFormatter.parseLocalDateTime(start)
												val endDate = datetimeFormatter.parseLocalDateTime(stop)

												val validValues = results.dropWhile(entry => entry._1 <= startDate)
												.takeWhile(entry => entry._1 <= endDate)
												.map(x => dtf.print(x._1) + ";" + x._2 + ";" + x._3.toString)
												Source(validValues)
									}

									case _ => Source(List("invalid segments: " + segments.mkString("/")))
									}

									complete(
											HttpEntity(
													ContentTypes.`text/csv(UTF-8)`,
													answer.map(a => ByteString(s"$a\n"))
													)
											)
									}
								}
							}
								}

						lastRoute ~ fullRoute
					}
				}
			}
				}
}