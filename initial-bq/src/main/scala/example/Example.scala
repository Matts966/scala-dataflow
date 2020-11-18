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


import scala.reflect.runtime.universe.{typeOf, TypeTag, Type}
import scala.reflect.classTag
import scala.reflect.ClassTag

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
import org.apache.beam.sdk.extensions.sql.BeamSqlUdf
import org.joda.time.{Instant, LocalDate}


object Example {
  // Class cannot be used as valid map key so use toString.
  def PRIMITIVE_TYPES(typ: Type): FieldType = {
    if (typ =:= typeOf[String]) FieldType.STRING
    else Map(
      typeOf[Byte] -> FieldType.BYTE,
      typeOf[Short] -> FieldType.INT16,
      typeOf[Int] -> FieldType.INT32,
      typeOf[Long] -> FieldType.INT64,
      typeOf[Float] -> FieldType.FLOAT,
      typeOf[Double] -> FieldType.DOUBLE,
      typeOf[Boolean] -> FieldType.BOOLEAN,
      typeOf[BigDecimal] -> FieldType.DECIMAL,
      typeOf[Instant] -> FieldType.DATETIME,
      typeOf[LocalDate] -> FieldType.DATETIME,
    )(typ)
  }

  // Annotate input class with schema inferred from a BigQuery SELECT.
  // Class `Row` will be expanded into a case class with fields from the SELECT query. A companion
  // object will also be generated to provide easy access to original query/table from annotation,
  // `TableSchema` and converter methods between the generated case class and `TableRow`.
  @BigQueryType.fromQuery("""
WITH
  UNIQUE AS (
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
      DATE_SUB(publishing_date, INTERVAL 8 week) AS deemed_prediction_date,
      *,
      DATE_SUB(LAG(publishing_date) OVER (
          ORDER BY publishing_date DESC), INTERVAL 1 DAY) AS end_date,
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

  def toRow[T](r: T, schema: BSchema)(implicit tt: TypeTag[T], ct: ClassTag[T]): Row = {
    val rs = Row.withSchema(schema)
    typeOf[T].members.sorted.filter(!_.isMethod).foreach(
      field => {
        val name = field.name.toString.trim
        val fld = classTag[T].runtimeClass.getDeclaredField(name)
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
  }

  def buildSchema[T: TypeTag]: BSchema = {
    val schemaBuilder = BSchema.builder()
    typeOf[T].members.sorted.filter(!_.isMethod).foreach(
      field => {
        val name = field.name.toString.trim
        println(name, field.typeSignature)
        field.typeSignature match {
          case typ if typ <:< typeOf[Option[Any]] => {
            schemaBuilder.addNullableField(name, PRIMITIVE_TYPES(typ.typeArgs.head))
          }
          case r => {
            schemaBuilder.addField(name, PRIMITIVE_TYPES(r))
          }
        }
      }
    )
    schemaBuilder.build()
  }

  def localDateToDateTime(obj: Object): Object = {
    if (obj == null || !obj.isInstanceOf[LocalDate]) {
      obj
    } else {
      obj.asInstanceOf[LocalDate].toDateTimeAtStartOfDay()
    }
  }

  class Error extends BeamSqlUdf {
    def eval(): Unit = {
      sys.error("error")
      sys.exit(1)
    }
  }

  class IsOver18Udf extends BeamSqlUdf {
    def eval(input: Integer): Boolean = input >= 18
  }

  def main(cmdlineArgs: Array[String]): Unit = {
    val (opts, args) = ScioContext.parseArguments[PipelineOptions](cmdlineArgs)
    opts.as(classOf[BeamSqlPipelineOptions])
      .setPlannerName("org.apache.beam.sdk.extensions.sql.zetasql.ZetaSQLQueryPlanner")
    val sc = ScioContext(opts)

    val schema = buildSchema[dm_send_date_list]
    implicit val coderRow = Coder.row(schema)

    sc.typedBigQueryStorage[dm_send_date_list]()
      .map(toRow(_, schema))
      //.map(r => Row.withSchema(schema).addValue(r.famiy_code.orNull).build())
      .applyTransform(SqlTransform.query("""-- not null、ユニーク性のチェック(history_id)
with uniqueness as (
SELECT
  count(1) as count
FROM
  PCOLLECTION
group by
  publishing_date
),

num_rows as (
  SELECT
    count(1) as num_rows
  FROM
    PCOLLECTION
)

select
  if(sum(uniqueness.count) = num_rows.num_rows, True, Error("the column history_id can include not null rows or duplicated data."))
FROM
  uniqueness
  cross join
  num_rows
group by
  num_rows.num_rows
;"""))

  // .applyTransform(SqlTransform.query("""select isUserOver18(10) as isOver18, e() from PCOLLECTION""").registerUdf("e", classOf[Error]).registerUdf("isUserOver18", classOf[IsOver18Udf]))

    val result = sc.run()
  }
}
