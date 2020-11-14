package com.example;

import com.google.api.services.bigquery.model.TableRow;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlPipelineOptions;
import org.apache.beam.sdk.extensions.sql.SqlTransform;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.coders.CoderRegistry;


public class Example {

    public interface BigQueryToGCSOptions extends PipelineOptions {}

    public static void main(final String[] args) {
        final BigQueryToGCSOptions options = PipelineOptionsFactory.fromArgs(args)
                                                                   .withValidation()
                                                                   .as(BigQueryToGCSOptions.class);
        options
            .as(BeamSqlPipelineOptions.class)
            .setPlannerName("org.apache.beam.sdk.extensions.sql.zetasql.ZetaSQLQueryPlanner");

        final Pipeline pipeline = Pipeline.create(options);

        pipeline.apply("Read from BigQuery", BigQueryIO.readTableRows()
                            .fromQuery("""
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
""").usingStandardSql())
                .apply(ParDo.of(new DoFn<TableRow, Row>() {
                    @ProcessElement
                    public void processElement(final ProcessContext c) {
                        final TableRow row = c.element();
                        Schema s = Schema.builder()
                                .addInt64Field("year")
                                .build();
                        final Row newRow = Row.withSchema(s)
                            .addValue(String.valueOf(row.get("year")))
                            .build();
                        c.output(newRow);
                    }}))
                .apply(SqlTransform.query("SELECT year FROM PCOLLECTION"));

    pipeline.run();
  }
}
