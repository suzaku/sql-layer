/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

/**
 *  This class generates test-arith-typing.yaml, which tests for the output
 *  type of all possible arithmetics with different datatypes including
 *  mixing date/times and numeric
 *
 *  <b>TODO</b>: 
 *       1) remove commented-out test cases when INTERVAL type becomes available
 *       2) add test for BOOLEAN
 *       3) add % operator when it becomes available (simply add the '%' symbol to ops[])
 *
 */
public class ArithTypeTestGenerator
{
    static private final String ops [] = {"+", "-", "/", "*"};
    static private final String numerics[] = {"numeric", "double_field",
          "bigint_field", "integer_field" };
    static private final String dates[] = {"date_field", "time_field", "timestamp_field"};
    static private final String intervals[] = {"INTERVAL 1 YEAR", "INTERVAL 1 MONTH",
        "INTERVAL 1 QUARTER", "INTERVAL 1 WEEK", "INTERVAL 1 DAY",
        "INTERVAL 1 HOUR", "INTERVAL 1 MINUTE", "INTERVAL 1 SECOND"};
    static private final String text = "varchar_field";
    static private final String bool = "boolean_field";

    static private int count = 0; // keep track of total number of tests generated
    static private int stmNumber = 3; // keep track of enabled tests
    
    public static void main (String args[]) throws IOException
    {
        count = 0;
        StringBuilder output = new StringBuilder();
        writeHeaders(output);
        writeTestCases(output);
        output.append("..." + System.getProperty("line.separator"));
        output.insert(0, "## Tests generated: " + count + " cases\n" +
                         "## Tests enabled: " + (stmNumber - 3) + "\n");

        String path = System.getProperty("user.dir")
                    + "/src/test/resources/com/akiban/sql/pg/yaml/functional"
                    + "/test-arith-typing.yaml";
        saveFile(output, path);
        System.out.println(output);
    }

    private static void writeHeaders (StringBuilder out)
    {
        out.append("## Generated by " + Thread.currentThread().getStackTrace()[1].getClassName() + ".java"
                + System.getProperty("line.separator")
                + "## DataType Test" + System.getProperty("line.separator")
                + "---" + System.getProperty("line.separator")
                + "- Include: all-types-schema.yaml" + System.getProperty("line.separator")
                + "---" + System.getProperty("line.separator")
                + "- Statement: INSERT INTO all_types(" + getFieldList() + ")"
                + " VALUES (" + getValueList() + ");" + System.getProperty("line.separator")
                );
    }

    private static String getFieldList()
    {
        int n = 0;
        StringBuilder builder = new StringBuilder();
        for (; n < numerics.length ; ++n)
            builder.append(numerics[n] + ", ");

        for (n = 0; n < dates.length; ++n)
            builder.append(dates[n] + ", ");

        builder.append(text);
        return builder.toString();
    }

    private static String getValueList()
    {
        StringBuilder builder = new StringBuilder();

        for (int n = 0; n < numerics.length; ++n)
            builder.append("\'1\',");

        // date
        builder.append("\'2012-01-20\', ");

        // time
        builder.append("\'04:30:10\', ");

        // datetime
        builder.append("\'2012-01-20 04:30:10\', ");

        // varchar
        builder.append("\'1\'");
        return builder.toString();
    }

    private static void writeTestCases (StringBuilder out)
    {
       // numeric types
        for (int left = 0; left < numerics.length; ++ left)
        {
            for (String op : ops)
            {
                // numeric and numeric
                for (int right = 0; right < numerics.length; ++right)
                {
                    writeSelectStmt(numerics[left], op, numerics[right], out);
                    writeOutput("output_types",
                             numerics[left < right ? left : right].replace("_field", "").toUpperCase(),
                             out);
                }

                // numeric and varchar
                reverseLeftRight(numerics[left], op, text, out, false,
                        "output_types", numerics[left].replace("_field", "").toUpperCase());
            }
            // numeric and interval
            // These tests are *temporarily* commented  out due to the lack
            // of interval type
            for (String interval : intervals)
            {
                // interval plus/minus numeric is invalid
                for (String op : Arrays.asList("+", "-"))
                    reverseLeftRight(numerics[left], op,interval, out, true,
                            "error", "22503");

                // interval * numeric, or numeric * interval
                reverseLeftRight (numerics[left], "*", interval, out, true,
                        "output_types", numerics[left].replace("_field", "").toUpperCase());

                // numeric / interval is invalid
                writeSelectStmt(numerics[left], "/", interval, out, true);
                writeOutput("error", "22503", out, true);

                // interval / numeric is allowed
                writeSelectStmt(interval, "/", numerics[left], out, true);
                writeOutput("output_types", numerics[left].replace("_field", "").toUpperCase(), out, true);
            }
        }

        // date/time plus/minus interval
        String sDate = "\'2012-01-23\'";
        String sDatetime = "\'2012-01-23 12:30:10\'";
        String varchars[] = {sDate, sDatetime};
        for (String op: Arrays.asList("+", "-"))
            for (String interval : intervals)
            {
                for (String date : dates)
                {
                    // date/time plus/minus interval
                    writeSelectStmt(date, op, interval, out);
                    writeOutput("output_types",
                            date.replace("_field", "").toUpperCase(),
                            out);
                }

                // varchar plus/minus interval
                writeSelectStmt(sDate, op, interval, out);
                writeOutput("output_types", "DATE", out);

                writeSelectStmt(sDatetime, op, interval, out);
                writeOutput("output_types", "TIMESTAMP", out);
            }

        // interval plus/minus date/time
        for (String interval : intervals)
        {
            // interval + VARCHAR (%Y-%m-%d)
            writeSelectStmt(interval, "+", sDate, out);
            writeOutput("output_types", "DATE", out);

            // interval + VARCHAR (%Y-%m-%d HH:mm:ss)
            writeSelectStmt(interval, "+", sDatetime, out);
            writeOutput("output_types", "TIMESTAMP", out);

            // interval - VARCHAR
            // expect exception
            for (String string : varchars)
            {
                writeSelectStmt(interval, "-", string, out);
                writeOutput("error", "22503", out);
            }

            for (String date : dates)
            {
                // interval  + date/time
                writeSelectStmt(interval, "+", date, out);
                writeOutput("output_types",
                        date.replace("_field", "").toUpperCase(),
                        out);

                // interval - date/time
                // expect exception
                writeSelectStmt(interval, "-", date, out);
                writeOutput("error", "22503", out);
            }
        }
        // interval times/divide by date/time (or vice versa)
        // expect exception
        for (String interval : intervals)
            for (String date : dates)
                for (String op : Arrays.asList("*", "/"))
                {
                    writeSelectStmt(interval, op, date, out);
                    writeOutput("error", "22503", out);

                    writeSelectStmt(date, op, interval, out);
                    writeOutput("error", "22503", out);
                }

        // date/time and numeric
        // expect exception
        for (String num : numerics)
            for (String date : dates)
            {
                for (String op: ops)
                {
                    writeSelectStmt(num, op, date, out);
                    writeOutput("error", "22503", out);

                    writeSelectStmt(date, op, num, out);
                    writeOutput("error", "22503", out);
                }
            }

        // date/time and date/time
        for (String date1 : dates)
        {
            for (String date2 : dates)
            {
                // date/time plus/times/dvide by date/time
                // expect exception
                for (String op : Arrays.asList("+", "*", "/"))
                {
                    writeSelectStmt(date1, op, date2, out);
                    writeOutput("error", "22503", out);
                }
                // date/time minus date/time of different type
                // expect exception
                if (date1.equals(date2)) continue;
                writeSelectStmt(date1, "-", date2, out);
                writeOutput("error", "22503", out);
            }

            // date/time minus date/time of the same
            // type, expect interval_millis
            // Since INTERVAl type is not available,
            // this test is disabled for now
            writeSelectStmt(date1, "-", date1, out, true);
            writeOutput("output_types", "INTERVAL_MILLIS", out, true);
        }
    }
    private static void writeSelectStmt (String arg1, String op, String arg2, StringBuilder out)
    {
        writeSelectStmt(arg1, op,arg2, out, false);
    }

    private static void writeSelectStmt (String arg1, String op, String arg2, StringBuilder out, boolean commentOut)
    {
        if (commentOut)
            out.append("#---" + System.getProperty("line.separator") + "#");
        else
        {
            out.append("---" + System.getProperty("line.separator"));
            out.append("#command number: " + (stmNumber++) + System.getProperty("line.separator"));
        }
        out.append("- Statement: SELECT ");
        out.append(arg1 + " " + op + " " + arg2 + " FROM all_types;" + System.getProperty("line.separator"));
        ++count;
    }

    private static void reverseLeftRight (String left, String op, String right, StringBuilder out, boolean commentOut,
            String outType, String result)
    {
        writeSelectStmt(left, op, right, out, commentOut);
        writeOutput(outType, result, out, commentOut);

        writeSelectStmt(right, op, left, out, commentOut);
        writeOutput(outType, result, out, commentOut);
    }

    private static void writeOutput (String outType, String result, StringBuilder out)
    {
        writeOutput(outType, result, out, false);
    }

    private static void writeOutput (String outType, String result, StringBuilder out, boolean commentOut)
    {
        if (commentOut)
            out.append("#");
         out.append("- " + outType + ": [" + result +"]" + System.getProperty("line.separator"));
    }

    private static void saveFile (StringBuilder data, String name) throws IOException
    {
        FileWriter fstream = new FileWriter(name);
        BufferedWriter out = new BufferedWriter(fstream);
        out.write(data.toString());
        out.close();
        System.out.println("\nFile saved at: " + (new File(name)).getCanonicalPath()
                + System.getProperty("line.separator"));
    }
}

