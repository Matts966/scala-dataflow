/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Example: Read and write using typed BigQuery API with annotated case classes
// Usage:

// `sbt "runMain com.spotify.scio.examples.extra.TypedBigQueryTornadoes
// --project=[PROJECT] --runner=DataflowRunner --zone=[ZONE]
// --output=[PROJECT]:[DATASET].[TABLE]"`
package example

import scala.reflect.runtime.universe.{typeOf, TypeTag}

import com.spotify.scio.ScioContext
import com.spotify.scio.bigquery._
import com.spotify.scio.ContextAndArgs
import com.spotify.scio.schemas
import com.spotify.scio.coders.Coder

import org.apache.beam.sdk.extensions.sql.impl.BeamSqlPipelineOptions
import org.apache.beam.sdk.extensions.sql.SqlTransform
import org.apache.beam.sdk.values.{PCollectionTuple, TupleTag}
import org.apache.beam.sdk.values.Row
import org.apache.beam.sdk.schemas.{Schema => BSchema}
import org.apache.beam.sdk.options.PipelineOptions
import org.apache.beam.sdk.schemas.Schema.FieldType
import org.joda.time.{Instant, LocalDate}


object Example {
  // Class cannot be used as valid map key so use toString.
  val PRIMITIVE_TYPES = Map(
    "Byte" -> FieldType.BYTE,
    "Short" -> FieldType.INT16,
    "Int" -> FieldType.INT32,
    "Long" -> FieldType.INT64,
    "Float" -> FieldType.FLOAT,
    "Double" -> FieldType.DOUBLE,
    "Boolean" -> FieldType.BOOLEAN,
    "BigDecimal" -> FieldType.DECIMAL,
  )

  // Annotate input class with schema inferred from a BigQuery SELECT.
  // Class `Row` will be expanded into a case class with fields from the SELECT query. A companion
  // object will also be generated to provide easy access to original query/table from annotation,
  // `TableSchema` and converter methods between the generated case class and `TableRow`.
  @BigQueryType.fromQuery("""
WITH
  UNIQUE AS (
    -- 世帯間によって異なっているsend_dateをMAXで統一
    SELECT
      monthly_number_name,
      MAX(send_date) AS publishing_date,
      COUNT(1) AS cnt,
      SUM(COUNT(1)) OVER (PARTITION BY monthly_number_name) AS total_cnt_by_publish,
      send_class_name
    FROM
      gcb_dev_warehouse.v_dm_send_family
    GROUP BY
      monthly_number_name,
      send_class_name
  ),
  dm_send_date_list AS (
    SELECT
      CAST(SUBSTR(monthly_number_name, 0, 4) AS int64) AS year,
      CAST(SUBSTR(monthly_number_name, 6, 2) AS int64) AS month,
      -- 本誌の発送先を予測する日付(発送日の８週間前に予測する必要がある)
      DATE_SUB(publishing_date, INTERVAL 8 week) AS deemed_prediction_date,
      *,
      -- 前月号のpublishing_date
      DATE_SUB(LAG(publishing_date) OVER (
          ORDER BY publishing_date DESC), INTERVAL 1 DAY) AS end_date,
      -- 最新と前月号のpublishing_dateの差分
      DATE_DIFF(LAG(publishing_date) OVER (
          ORDER BY publishing_date DESC), publishing_date, DAY) - 1 AS duration
    FROM
      UNIQUE
    WHERE
      send_class_name = '1_本発送'
  )
SELECT
  *
FROM
  dm_send_date_list
ORDER BY publishing_date;
""")
  class dm_send_date_list

  def bigQueryType = BigQueryType[dm_send_date_list]

  def localDateToDateTime(obj: Object): Object = {
    if (obj == null || !obj.isInstanceOf[LocalDate]) {
      obj
    } else {
      obj.asInstanceOf[LocalDate].toDateTimeAtStartOfDay()
    }
  }

  def main(cmdlineArgs: Array[String]): Unit = {
    val (opts, args) = ScioContext.parseArguments[PipelineOptions](cmdlineArgs)
    opts.as(classOf[BeamSqlPipelineOptions])
      .setPlannerName("org.apache.beam.sdk.extensions.sql.zetasql.ZetaSQLQueryPlanner")
    val sc = ScioContext(opts)

    val schemaBuilder = BSchema.builder()
    typeOf[dm_send_date_list].members.sorted.filter(!_.isMethod).foreach(
      field => {
        val name = field.name.toString
        println(name, field.typeSignature)
        field.typeSignature match {
          case typ if typeOf[String] =:= typ => schemaBuilder.addStringField(name)
          case typ if typeOf[Option[String]] =:= typ => schemaBuilder.addNullableField(name, FieldType.STRING)
          case typ if typeOf[Instant] =:= typ || typeOf[LocalDate] =:= typ => schemaBuilder.addDateTimeField(name)
          case typ if typeOf[Option[Instant]] =:= typ || typeOf[Option[LocalDate]] =:= typ => schemaBuilder.addNullableField(name, FieldType.DATETIME)
          case typ if typ <:< typeOf[Option[Any]] => {
            println(typ.typeArgs.head)
            schemaBuilder.addNullableField(name, PRIMITIVE_TYPES(typ.typeArgs.head.toString))
          }
          case r => {
            println("raw", r)
            schemaBuilder.addField(name, PRIMITIVE_TYPES(r.toString))
          }
        }
      }
    )
    val schema = schemaBuilder.build()
    println(schema)
    implicit val coderRow = Coder.row(schema)

    

    sc.typedBigQueryStorage[dm_send_date_list]()
      .map(r => {
        val rs = Row.withSchema(schema)
        typeOf[dm_send_date_list].members.sorted.filter(!_.isMethod).foreach(
          field => {
            val name = field.name.toString.trim
            val fld = classOf[dm_send_date_list].getDeclaredField(name)
            fld.setAccessible(true)
            val v = fld.get(r)
            if (v.isInstanceOf[Option[Object]]) {
              rs.addValue(localDateToDateTime(v.asInstanceOf[Option[Object]].getOrElse(null)))
            } else {
              rs.addValue(localDateToDateTime(v))
            }
          }
        )
        rs.build()
      })
      //.map(r => Row.withSchema(schema).addValue(r.famiy_code.orNull).build())
      .applyTransform(SqlTransform.query("""-- not null、ユニーク性のチェック(dm_send_history_id)
SELECT * FROM PCOLLECTION
;"""))

    val result = sc.run()
  }
}
