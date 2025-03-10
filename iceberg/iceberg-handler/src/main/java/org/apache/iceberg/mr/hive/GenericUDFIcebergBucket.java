/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iceberg.mr.hive;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentLengthException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorConverters;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorConverter;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.WritableConstantIntObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.iceberg.transforms.Transform;
import org.apache.iceberg.transforms.Transforms;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * GenericUDFIcebergBucket - UDF that wraps around Iceberg's bucket transform function
 */
@Description(name = "iceberg_bucket",
    value = "_FUNC_(value, bucketCount) - " +
        "Returns the bucket value calculated by Iceberg bucket transform function ",
    extended = "Example:\n  > SELECT _FUNC_('A bucket full of ice!', 5);\n  4")
public class GenericUDFIcebergBucket extends GenericUDF {
  private final IntWritable result = new IntWritable();
  private int numBuckets = -1;
  private transient PrimitiveObjectInspector argumentOI;
  private transient ObjectInspectorConverters.Converter converter;

  @FunctionalInterface
  private interface UDFEvalFunction<T> {
    void apply(T argument) throws HiveException;
  }

  private transient UDFEvalFunction<DeferredObject> evaluator;

  @Override
  public ObjectInspector initialize(ObjectInspector[] arguments) throws UDFArgumentException {
    if (arguments.length != 2) {
      throw new UDFArgumentLengthException(
          "ICEBERG_BUCKET requires 2 arguments (value, bucketCount), but got " + arguments.length);
    }

    numBuckets = getNumBuckets(arguments[1]);

    if (arguments[0].getCategory() != ObjectInspector.Category.PRIMITIVE) {
      throw new UDFArgumentException(
          "ICEBERG_BUCKET first argument takes primitive types, got " + argumentOI.getTypeName());
    }
    argumentOI = (PrimitiveObjectInspector) arguments[0];

    PrimitiveObjectInspector.PrimitiveCategory inputType = argumentOI.getPrimitiveCategory();
    ObjectInspector outputOI = null;
    switch (inputType) {
      case CHAR:
      case VARCHAR:
      case STRING:
        converter = new PrimitiveObjectInspectorConverter.StringConverter(argumentOI);
        Transform<String, Integer> stringTransform = Transforms.bucket(Types.StringType.get(), numBuckets);
        evaluator = arg -> {
          String val = (String) converter.convert(arg.get());
          result.set(stringTransform.apply(val));
        };
        break;

      case BINARY:
        converter = new PrimitiveObjectInspectorConverter.BinaryConverter(argumentOI,
            PrimitiveObjectInspectorFactory.writableBinaryObjectInspector);
        Transform<ByteBuffer, Integer> byteBufferTransform = Transforms.bucket(Types.BinaryType.get(), numBuckets);
        evaluator = arg -> {
          BytesWritable val = (BytesWritable) converter.convert(arg.get());
          ByteBuffer byteBuffer = ByteBuffer.wrap(val.getBytes(), 0, val.getLength());
          result.set(byteBufferTransform.apply(byteBuffer));
        };
        break;

      case INT:
        converter = new PrimitiveObjectInspectorConverter.IntConverter(argumentOI,
            PrimitiveObjectInspectorFactory.writableIntObjectInspector);
        Transform<Integer, Integer> intTransform = Transforms.bucket(Types.IntegerType.get(), numBuckets);
        evaluator = arg -> {
          IntWritable val = (IntWritable) converter.convert(arg.get());
          result.set(intTransform.apply(val.get()));
        };
        break;

      case LONG:
        converter = new PrimitiveObjectInspectorConverter.LongConverter(argumentOI,
            PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        Transform<Long, Integer> longTransform = Transforms.bucket(Types.LongType.get(), numBuckets);
        evaluator = arg -> {
          LongWritable val = (LongWritable) converter.convert(arg.get());
          result.set(longTransform.apply(val.get()));
        };
        break;

      case DECIMAL:
        DecimalTypeInfo decimalTypeInfo = (DecimalTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(argumentOI);
        Type.PrimitiveType decimalIcebergType = Types.DecimalType.of(decimalTypeInfo.getPrecision(),
            decimalTypeInfo.getScale());

        converter = new PrimitiveObjectInspectorConverter.HiveDecimalConverter(argumentOI,
            PrimitiveObjectInspectorFactory.writableHiveDecimalObjectInspector);
        Transform<BigDecimal, Integer> bigDecimalTransform = Transforms.bucket(decimalIcebergType, numBuckets);
        evaluator = arg -> {
          HiveDecimalWritable val = (HiveDecimalWritable) converter.convert(arg.get());
          result.set(bigDecimalTransform.apply(val.getHiveDecimal().bigDecimalValue()));
        };
        break;

      case FLOAT:
        converter = new PrimitiveObjectInspectorConverter.FloatConverter(argumentOI,
            PrimitiveObjectInspectorFactory.writableFloatObjectInspector);
        Transform<Float, Integer> floatTransform = Transforms.bucket(Types.FloatType.get(), numBuckets);
        evaluator = arg -> {
          FloatWritable val = (FloatWritable) converter.convert(arg.get());
          result.set(floatTransform.apply(val.get()));
        };
        break;

      case DOUBLE:
        converter = new PrimitiveObjectInspectorConverter.DoubleConverter(argumentOI,
            PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);
        Transform<Double, Integer> doubleTransform = Transforms.bucket(Types.DoubleType.get(), numBuckets);
        evaluator = arg -> {
          DoubleWritable val = (DoubleWritable) converter.convert(arg.get());
          result.set(doubleTransform.apply(val.get()));
        };
        break;

      default:
        throw new UDFArgumentException(
            " ICEBERG_BUCKET() only takes STRING/CHAR/VARCHAR/BINARY/INT/LONG/DECIMAL/FLOAT/DOUBLE" +
                " types as first argument, got " + inputType);
    }

    outputOI = PrimitiveObjectInspectorFactory.writableIntObjectInspector;
    return outputOI;
  }

  private static int getNumBuckets(ObjectInspector arg) throws UDFArgumentException {
    UDFArgumentException udfArgumentException = new UDFArgumentException("ICEBERG_BUCKET() second argument can only " +
        "take an int type, but got " + arg.getTypeName());
    if (arg.getCategory() != ObjectInspector.Category.PRIMITIVE) {
      throw udfArgumentException;
    }
    PrimitiveObjectInspector.PrimitiveCategory inputType = ((PrimitiveObjectInspector) arg).getPrimitiveCategory();
    if (inputType != PrimitiveObjectInspector.PrimitiveCategory.INT) {
      throw udfArgumentException;
    }
    return ((WritableConstantIntObjectInspector) arg).getWritableConstantValue().get();
  }

  @Override
  public Object evaluate(DeferredObject[] arguments) throws HiveException {

    DeferredObject argument = arguments[0];
    if (argument == null) {
      return null;
    } else {
      evaluator.apply(argument);
    }

    return result;
  }

  @Override
  public String getDisplayString(String[] children) {
    return getStandardDisplayString("iceberg_bucket", children);
  }
}
