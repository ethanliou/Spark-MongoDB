/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.datasource.mongodb.partitioner

import com.mongodb.casbah.Imports._
import com.mongodb.{MongoCredential, ServerAddress}
import com.stratio.datasource.mongodb.client.MongodbClientFactory
import com.stratio.datasource.mongodb.client.MongodbClientFactory.Client
import com.stratio.datasource.mongodb.config.{MongodbSSLOptions, MongodbCredentials, MongodbConfig}
import com.stratio.datasource.mongodb.partitioner.MongodbPartitioner._
import com.stratio.datasource.partitioner.{PartitionRange, Partitioner}
import com.stratio.datasource.util.Config

import scala.util.Try

/**
 * @param config Partition configuration
 */
class MongodbPartitioner(config: Config) extends Partitioner[MongodbPartition] {

  @transient private val hosts: List[ServerAddress] =
    config[List[String]](MongodbConfig.Host)
      .map(add => new ServerAddress(add))

  @transient private val credentials: List[MongoCredential] =
    config.getOrElse[List[MongodbCredentials]](MongodbConfig.Credentials, MongodbConfig.DefaultCredentials).map {
      case MongodbCredentials(user, database, password) =>
        MongoCredential.createCredential(user, database, password)
    }

  @transient private val ssloptions: Option[MongodbSSLOptions] =
    config.get[MongodbSSLOptions](MongodbConfig.SSLOptions)

  private val clientOptions = config.properties //config.properties.filterKeys(_.contains(MongodbConfig.ListMongoClientOptions)) // TODO review this Map. Can't filter keys

  private val databaseName: String = config(MongodbConfig.Database)

  private val collectionName: String = config(MongodbConfig.Collection)

  private val collectionFullName: String = s"$databaseName.$collectionName"

  private val connectionsTime = config.get[String](MongodbConfig.ConnectionsTime).map(_.toLong)

  private val cursorBatchSize = config.getOrElse[Int](MongodbConfig.CursorBatchSize, MongodbConfig.DefaultCursorBatchSize)

  override def computePartitions(): Array[MongodbPartition] = {
    val mongoClient = MongodbClientFactory.getClient(hosts, credentials, ssloptions, clientOptions)._2

    val result = if (isShardedCollection(mongoClient))
      computeShardedChunkPartitions(mongoClient)
    else
      computeNotShardedPartitions(mongoClient)

    result
  }

  /**
   * @return Whether this is a sharded collection or not
   */
  protected def isShardedCollection(mongoClient: Client): Boolean = {

    val collection = mongoClient(databaseName)(collectionName)
    val isSharded = collection.stats.ok && collection.stats.getBoolean("sharded", false)

    isSharded
  }

  /**
   * @return MongoDB partitions as sharded chunks.
   */
  protected def computeShardedChunkPartitions(mongoClient: Client): Array[MongodbPartition] = {

    val partitions = Try {
      val chunksCollection = mongoClient(ConfigDatabase)(ChunksCollection)
      val dbCursor = chunksCollection.find(MongoDBObject("ns" -> collectionFullName))
      val shards = describeShardsMap(mongoClient)
      val partitions = dbCursor.zipWithIndex.map {
        case (chunk: DBObject, i: Int) =>
          val lowerBound = chunk.getAs[DBObject]("min")
          val upperBound = chunk.getAs[DBObject]("max")
          val hosts: Seq[String] = (for {
            shard <- chunk.getAs[String]("shard")
            hosts <- shards.get(shard)
          } yield hosts).getOrElse(Seq[String]())

          MongodbPartition(i,
            hosts,
            PartitionRange(lowerBound, upperBound))
      }.toArray

      dbCursor.close()

      partitions
    }.recover {
      case _: Exception =>
        val serverAddressList: Seq[String] = mongoClient.allAddress.map {
          server => server.getHost + ":" + server.getPort
        }.toSeq
        Array(MongodbPartition(0, serverAddressList, PartitionRange(None, None)))
    }.get

    partitions
  }

  /**
   * @return Array of not-sharded MongoDB partitions.
   */
  protected def computeNotShardedPartitions(mongoClient: Client): Array[MongodbPartition] = {
    val ranges = splitRanges(mongoClient)
    val serverAddressList: Seq[String] = mongoClient.allAddress.map {
      server => server.getHost + ":" + server.getPort
    }.toSeq
    val partitions: Array[MongodbPartition] = ranges.zipWithIndex.map {
      case ((previous: Option[DBObject], current: Option[DBObject]), i) =>
        MongodbPartition(i,
          serverAddressList,
          PartitionRange(previous, current))
    }.toArray

    partitions
  }

  /**
   * @return A sequence of minimum and maximum DBObject in range.
   */
  protected def splitRanges(mongoClient: Client): Seq[(Option[DBObject], Option[DBObject])] = {

    val cmd: MongoDBObject = MongoDBObject(
      "splitVector" -> collectionFullName,
      "keyPattern" -> MongoDBObject(config.getOrElse(MongodbConfig.SplitKey, MongodbConfig.DefaultSplitKey) -> 1),
      "force" -> false,
      "maxChunkSize" -> config.getOrElse(MongodbConfig.SplitSize, MongodbConfig.DefaultSplitSize)
    )
    val ranges = Try {
      val data = mongoClient("admin").command(cmd)
      val splitKeys = data.as[List[DBObject]]("splitKeys").map(Option(_))
      val ranges = (None +: splitKeys) zip (splitKeys :+ None)

      ranges.toSeq
    }.recover {
      case _: Exception =>
        val stats = mongoClient(databaseName)(collectionName).stats
        val shards = mongoClient(ConfigDatabase)(ShardsCollection)
          .find(MongoDBObject("_id" -> stats.getString("primary"))).batchSize(cursorBatchSize)
        val shard = shards.next()
        val shardHost: String = shard.as[String]("host").replace(shard.get("_id") + "/", "")
        val (shardClientKey, shardClient) = MongodbClientFactory.getClient(shardHost)
        val data = shardClient.getDB("admin").command(cmd)
        val splitKeys = data.as[List[DBObject]]("splitKeys").map(Option(_))
        val ranges = (None +: splitKeys) zip (splitKeys :+ None)

        shards.close()

        MongodbClientFactory.setFreeConnectionByKey(shardClientKey, connectionsTime)

        ranges.toSeq
    }.getOrElse(Seq((None, None)))

    ranges
  }

  /**
   * @return Map of shards.
   */
  protected def describeShardsMap(mongoClient: Client): Map[String, Seq[String]] = {
    val shardsCollection = mongoClient(ConfigDatabase)(ShardsCollection)
    val shardsFind = shardsCollection.find()
    val shards = shardsFind.map { shard =>
      val hosts: Seq[String] = shard.getAs[String]("host")
        .fold(ifEmpty = Seq[String]())(_.split(",").map(_.split("/").reverse.head).toSeq)
      (shard.as[String]("_id"), hosts)
    }.toMap

    shardsFind.close()

    shards
  }
}

object MongodbPartitioner {

  val ConfigDatabase = "config"
  val ChunksCollection = "chunks"
  val ShardsCollection = "shards"
}
