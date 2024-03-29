package examples.alpakka

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Source, Sink}
import akka.stream.ActorMaterializer
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDb
import com.amazonaws.services.dynamodbv2.model._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}


object UpdateExample {
  implicit val system = ActorSystem("AlpakkaEample")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec = ExecutionContext.global

  def describe(table: String) = {
    DynamoDb.single(
      new DescribeTableRequest()
        .withTableName(table))
    .map {
      result => result
    }
    .recover {
      case e: Exception => {
        throw e
      }
    }
  }

  def status(table: String) : Future[String] = {
    describe(table)
      .map {
        results => {
          results.getTable().getTableStatus()
        }
      }
      .recover {
        case other => {
          throw other
        }
      }
  }

  def waitForStatus(table: String, expected: String) : Future[Done] = {
    status(table)
      .flatMap {
        case notExpected if notExpected != expected =>
          waitForStatus(table, expected)
        case _ => {
            Future.successful(Done)
          }
      }
  }

  def ensure_table(table: String) = {
    DynamoDb.single(
      new CreateTableRequest()
        .withTableName(table)
        .withKeySchema(
          List(
            new KeySchemaElement("partition", KeyType.HASH),
            new KeySchemaElement("sort", KeyType.RANGE)) asJava)
        .withAttributeDefinitions(
          List(
            new AttributeDefinition("partition", ScalarAttributeType.S),
            new AttributeDefinition("sort", ScalarAttributeType.S)) asJava)
        .withBillingMode("PAY_PER_REQUEST"))
    .map {
      result => {
        waitForStatus(table, "ACTIVE")
        result
      }
    }
    .recover {
      case inUse: ResourceInUseException => {
        Done
      }
      case e: Exception => {
        println(s"Failed to create $table.  Reason - $e")
        throw e
      }
    }
  }

  def main(args: Array[String]) = {
    val table = "foobar"

    println(s"Ensuring table $table exists...")
    ensure_table(table)
      .onComplete {
        _ => Done
      }

    val update =
      new TransactWriteItem()
        .withUpdate(
          new Update()
            .withTableName(table)
            .withKey(
              Map(
                "partition" -> new AttributeValue().withS("foo"),
                "sort" -> new AttributeValue().withS("bar")) asJava)
            .withExpressionAttributeNames(Map("#v" -> "value") asJava)
            .withExpressionAttributeValues(Map(":v" -> new AttributeValue().withS("test")) asJava)
            .withConditionExpression("attribute_exists(#v)") //This condition is expected to fail
            .withUpdateExpression("SET #v = :v")

    val request = new TransactWriteItemsRequest().withTransactItems(List(update) asJava)

    DynamoDb
      .single(request)
      .map {
        result => println(s"Result: $result")
      }
      .recover {
        case oops: AmazonDynamoDBException =>
          if( oops.isInstanceOf[TransactionCanceledException] ){
            // This branch is not reached, but expected to.
            println("TransactionCanceled!")
          } else if( oops.getErrorCode() == "TransactionCanceledException") {
            // This branch is reachable, however, one is unable
            // to easily access cancellation reasons.
            //
            // Note - The raw response object contains the expected type,
            // the type returned by alpakka is a generic AmazonDynamoDBException.
            println(s" RAW: ${oops.getRawResponseContent()}")i
          } else {
            println(s"Unhandled Exception - $oops")
          }
      }
      .onComplete {
        _ => System.exit(0)
      }
  }
}
