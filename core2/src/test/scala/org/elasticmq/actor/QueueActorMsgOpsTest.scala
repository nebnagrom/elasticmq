package org.elasticmq.actor

import org.elasticmq.actor.reply._
import org.elasticmq._
import org.elasticmq.message._
import org.elasticmq.actor.test.{DataCreationHelpers, QueueManagerForEachTest, ActorTest}
import org.joda.time.DateTime
import org.elasticmq.data.MessageData

class QueueActorMsgOpsTest extends ActorTest with QueueManagerForEachTest with DataCreationHelpers {

  waitTest("non-existent message should not be found") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)

      // When
      lookupResult <- queueActor ? LookupMessage(MessageId("xyz"))
    } yield {
      // Then
      lookupResult should be (None)
    }
  }

  waitTest("after persisting a message it should be found") {
    // Given
    val created = new DateTime(1216168602L)
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))
    val message = createMessageData("xyz", "123", MillisNextDelivery(123L)).copy(created = created)

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(message)

      // When
      lookupResult <- queueActor ? LookupMessage(MessageId("xyz"))
    } yield {
      // Then
      lookupResult should be (Some(message))
    }
  }

  waitTest("sending message with maximum size should succeed") {
    // Given
    val maxMessageContent = "x" * 65535

    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(createMessageData("xyz", maxMessageContent, MillisNextDelivery(123L)))

      // When
      lookupResult <- queueActor ? LookupMessage(MessageId("xyz"))
    } yield {
      // Then
      lookupResult should be (Some(createMessageData("xyz", maxMessageContent, MillisNextDelivery(123L))))
    }
  }

  waitTest("no undelivered message should not be found in an empty queue") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))
    val q2 = createQueueData("q2", MillisVisibilityTimeout(2L))

    for {
      Right(queueActor1) <- queueManagerActor ? CreateQueue(q1)
      Right(queueActor2) <- queueManagerActor ? CreateQueue(q2)
      _ <- queueActor1 ? SendMessage(createMessageData("xyz", "123", MillisNextDelivery(123L)))

      // When
      lookupResult <- queueActor2 ? ReceiveMessage(1000L, MillisNextDelivery(234L))
    } yield {
      // Then
      lookupResult should be (None)
    }
  }

  waitTest("undelivered message should be found in a non-empty queue") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))
    val q2 = createQueueData("q2", MillisVisibilityTimeout(2L))

    for {
      Right(queueActor1) <- queueManagerActor ? CreateQueue(q1)
      Right(queueActor2) <- queueManagerActor ? CreateQueue(q2)
      _ <- queueActor1 ? SendMessage(createMessageData("xyz", "123", MillisNextDelivery(123L)))

      // When
      lookupResult <- queueActor1 ? ReceiveMessage(200L, MillisNextDelivery(234L))
    } yield {
      // Then
      withoutDeliveryReceipt(lookupResult) should be (Some(createMessageData("xyz", "123", MillisNextDelivery(234L))))
    }
  }

  waitTest("next delivery should be updated after receiving") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(createMessageData("xyz", "123", MillisNextDelivery(123L)))

      // When
      _ <- queueActor ? ReceiveMessage(200L, MillisNextDelivery(567L))
      lookupResult <- queueActor ? LookupMessage(MessageId("xyz"))
    } yield {
      // Then
      withoutDeliveryReceipt(lookupResult) should be (Some(createMessageData("xyz", "123", MillisNextDelivery(567L))))
    }
  }

  waitTest("receipt handle should be filled when receiving") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)

      _ <- queueActor ? SendMessage(createMessageData("xyz", "123", MillisNextDelivery(123L)))

      // When
      lookupBeforeReceiving <- queueActor ? LookupMessage(MessageId("xyz"))
      received <- queueActor ? ReceiveMessage(200L, MillisNextDelivery(567L))
      lookupAfterReceiving <- queueActor ? LookupMessage(MessageId("xyz"))
    } yield {
      // Then
      lookupBeforeReceiving.flatMap(_.deliveryReceipt) should be (None)

      val receivedReceipt = received.flatMap(_.deliveryReceipt)
      val lookedUpReceipt = lookupAfterReceiving.flatMap(_.deliveryReceipt)

      receivedReceipt should be ('defined)
      lookedUpReceipt should be ('defined)

      receivedReceipt should be (lookedUpReceipt)
    }
  }

  waitTest("receipt handle should change on subsequent receives") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(createMessageData("xyz", "123", MillisNextDelivery(100L)))

      // When
      received1 <- queueActor ? ReceiveMessage(200L, MillisNextDelivery(300L))
      received2 <- queueActor ? ReceiveMessage(400L, MillisNextDelivery(500L))
    } yield {
      // Then
      val received1Receipt = received1.flatMap(_.deliveryReceipt)
      val received2Receipt = received2.flatMap(_.deliveryReceipt)

      received1Receipt should be ('defined)
      received2Receipt should be ('defined)

      received1Receipt should not be (received2Receipt)
    }
  }

  waitTest("delivered message should not be found in a non-empty queue when it is not visible") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(createMessageData("xyz", "123", MillisNextDelivery(123L)))

      // When
      receiveResult <- queueActor ? ReceiveMessage(100L, MillisNextDelivery(234L))
    } yield {
      // Then
      receiveResult should be (None)
    }
  }

  waitTest("increasing next delivery of a message") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))
    val m = createMessageData("xyz", "1234", MillisNextDelivery(123L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(m)

      // When
      _ <- queueActor ? UpdateNextDelivery(m.id, MillisNextDelivery(345L))
      lookupResult <- queueActor ? LookupMessage(MessageId("xyz"))
    } yield {
      // Then
      lookupResult should be (Some(createMessageData("xyz", "1234", MillisNextDelivery(345L))))
    }
  }

  waitTest("decreasing next delivery of a message") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))   // Initially m2 should be delivered after m1
    val m1 = createMessageData("xyz1", "1234", MillisNextDelivery(100L))
    val m2 = createMessageData("xyz2", "1234", MillisNextDelivery(200L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(m1)
      _ <- queueActor ? SendMessage(m2)

      // When
      _ <- queueActor ? UpdateNextDelivery(m2.id, MillisNextDelivery(50L))
      receiveResult <- queueActor ? ReceiveMessage(75L, MillisNextDelivery(100L))
    } yield {
      // Then
      // This should find the first message, as it has the visibility timeout decreased.
      receiveResult.map(_.id) should be (Some(m2.id))
    }
  }

  waitTest("message should be deleted") {
    // Given
    val q1 = createQueueData("q1", MillisVisibilityTimeout(1L))
    val m1 = createMessageData("xyz", "123", MillisNextDelivery(123L))

    for {
      Right(queueActor) <- queueManagerActor ? CreateQueue(q1)
      _ <- queueActor ? SendMessage(m1)

      // When
      _ <- queueActor ? DeleteMessage(m1.id)
      lookupResult <- queueActor ? LookupMessage(MessageId("xyz"))
    } yield {
      // Then
      lookupResult should be (None)
    }
  }

  def withoutDeliveryReceipt(messageOpt: Option[MessageData]) = {
    messageOpt.map(_.copy(deliveryReceipt = None))
  }
}
