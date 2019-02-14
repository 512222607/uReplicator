package kafka.mirrormaker

import java.util.concurrent.{BlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import kafka.common.{KafkaException, MessageSizeTooLargeException}
import kafka.consumer.{ConsumerTimeoutException, ConsumerTopicStatsRegistry, ZookeeperConsumerConnector}
import kafka.serializer.Decoder
import kafka.utils.{IteratorTemplate, Logging}
import org.apache.kafka.common.record.Record

class KafkaStreamIterator[K, V](private val channel: BlockingQueue[FetchedRecordsDataChunk],
                                consumerTimeoutMs: Int,
                                private val keyDecoder: Decoder[K],
                                private val valueDecoder: Decoder[V],
                                val clientId: String)
  extends IteratorTemplate[RecordAndMetadata[K, V]] with Logging {


  private val current: AtomicReference[Iterator[Record[K, V]]] = new AtomicReference(null)
  private var currentTopicInfo: PartitionTopicInfo2 = null
  private var consumedOffset: Long = -1L
  private val consumerTopicStats = ConsumerTopicStatsRegistry.getConsumerTopicStat(clientId)

  override def next(): RecordAndMetadata[K, V] = {
    val record = super.next()
    if(consumedOffset < 0)
      throw new KafkaException("Offset returned by the message set is invalid %d".format(consumedOffset))
    currentTopicInfo.resetConsumeOffset(consumedOffset)
    val topic = currentTopicInfo.topic
    trace("Setting %s consumed offset to %d".format(topic, consumedOffset))
    consumerTopicStats.getConsumerTopicStats(topic).messageRate.mark()
    consumerTopicStats.getConsumerAllTopicStats().messageRate.mark()
    record
  }

  protected def makeNext(): RecordAndMetadata[K, V] = {
    var currentDataChunk: FetchedRecordsDataChunk = null
    // if we don't have an iterator, get one
    var localCurrent = current.get()
    if(localCurrent == null || !localCurrent.hasNext) {
      if (consumerTimeoutMs < 0)
        currentDataChunk = channel.take
      else {
        currentDataChunk = channel.poll(consumerTimeoutMs, TimeUnit.MILLISECONDS)
        if (currentDataChunk == null) {
          // reset state to make the iterator re-iterable
          resetState()
          throw new ConsumerTimeoutException
        }
      }
      // TODO: WTF is this???
      if(currentDataChunk eq ZookeeperConsumerConnector.shutdownCommand) {
        debug("Received the shutdown command")
        return allDone
      } else {
        currentTopicInfo = currentDataChunk.topicInfo
        val cdcFetchOffset = currentDataChunk.fetchOffset
        val ctiConsumeOffset = currentTopicInfo.getConsumeOffset
        if (ctiConsumeOffset < cdcFetchOffset) {
          error("consumed offset: %d doesn't match fetch offset: %d for %s;\n Consumer may lose data"
            .format(ctiConsumeOffset, cdcFetchOffset, currentTopicInfo))
          currentTopicInfo.resetConsumeOffset(cdcFetchOffset)
        }
        localCurrent = currentDataChunk.records.records().asInstanceOf[Iterator[Record[K, V]]]

        current.set(localCurrent)
      }
      // TODO: WTF is valid bytes in messages
      // if we just updated the current chunk and it is empty that means the fetch size is too small!
      if(currentDataChunk.records.validBytes == 0)
        throw new MessageSizeTooLargeException("Found a message larger than the maximum fetch size of this consumer on topic " +
          "%s partition %d at fetch offset %d. Increase the fetch size, or decrease the maximum message size the broker will allow."
            .format(currentDataChunk.topicInfo.topic, currentDataChunk.topicInfo.partitionId, currentDataChunk.fetchOffset))
    }
    var record = localCurrent.next()
    // reject the messages that have already been consumed
    while (record.offset < currentTopicInfo.getConsumeOffset && localCurrent.hasNext) {
      record = localCurrent.next()
    }
    consumedOffset = record.offset() + 1

    record.ensureValid() // validate checksum of message to ensure it is valid

    new RecordAndMetadata(currentTopicInfo.topic,
      currentTopicInfo.partitionId,
      record,
      record.offset,
      keyDecoder,
      valueDecoder,
      record.timestamp(),
      record.timestampType)
  }

  def clearCurrentChunk() {
    debug("Clearing the current data chunk for this consumer iterator")
    current.set(null)
  }
}
