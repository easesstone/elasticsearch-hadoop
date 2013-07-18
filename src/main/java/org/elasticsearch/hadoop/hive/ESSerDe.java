/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch.hadoop.hive;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeStats;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.ListTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.StructTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.elasticsearch.hadoop.serialization.ContentBuilder;
import org.elasticsearch.hadoop.serialization.ValueWriter;
import org.elasticsearch.hadoop.serialization.json.JacksonJsonGenerator;
import org.elasticsearch.hadoop.util.BytesArray;
import org.elasticsearch.hadoop.util.FastByteArrayOutputStream;
import org.elasticsearch.hadoop.util.StringUtils;

@SuppressWarnings("deprecation")
public class ESSerDe implements SerDe {

    private Configuration conf;
    private StructObjectInspector inspector;
    private ArrayList<String> columnNames;

    // serialization artifacts
    private BytesArray scratchPad = new BytesArray(512);
    private ValueWriter<HiveType> valueWriter = new HiveValueWriter();
    private HiveType hiveType = new HiveType(null, null);
    private FastBytesWritable result = new FastBytesWritable();
    private StructTypeInfo structTypeInfo;

    @Override
    public void initialize(Configuration conf, Properties tbl) throws SerDeException {
        this.conf = conf;

        // extract column info - don't use Hive constants as they were renamed in 0.9 breaking compatibility

        // the column names are saved as the given inspector to #serialize doesn't preserves them (maybe because it's an external table)
        // use the class since StructType requires it ...
        columnNames = new ArrayList<String>(StringUtils.tokenize(tbl.getProperty(HiveConstants.COLUMNS), ","));
        List<TypeInfo> colTypes = TypeInfoUtils.getTypeInfosFromTypeString(tbl.getProperty(HiveConstants.COLUMNS_TYPES));

        // create a standard Object Inspector - note we're not using it for serialization/deserialization
        List<ObjectInspector> inspectors = new ArrayList<ObjectInspector>();

        for (TypeInfo typeInfo : colTypes) {
            inspectors.add(TypeInfoUtils.getStandardJavaObjectInspectorFromTypeInfo(typeInfo));
        }

        inspector = ObjectInspectorFactory.getStandardStructObjectInspector(columnNames, inspectors);
        structTypeInfo = (StructTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(inspector);
    }

    @Override
    public Object deserialize(Writable blob) throws SerDeException {
        if (blob == null || blob instanceof NullWritable) {
            return null;
        }

        StructTypeInfo structTypeInfo = (StructTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(inspector);
        return hiveFromWritable(structTypeInfo, blob);
    }

    @Override
    public ObjectInspector getObjectInspector() throws SerDeException {
        return inspector;
    }

    @Override
    public SerDeStats getSerDeStats() {
        // TODO: stats not yet supported (seems quite the trend for SerDe)
        return null;
    }

    @Override
    public Class<? extends Writable> getSerializedClass() {
        return FastBytesWritable.class;
    }

    @Override
    public Writable serialize(Object data, ObjectInspector objInspector) throws SerDeException {
        //overwrite field names (as they get lost by Hive)
        // StructTypeInfo structTypeInfo = (StructTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(objInspector);
        // structTypeInfo.setAllStructFieldNames(columnNames);

        // serialize the type directly to json
        scratchPad.reset();
        FastByteArrayOutputStream bos = new FastByteArrayOutputStream(scratchPad);

        //TODO: are there any bad side-effects of this caching  - note the given objInspector is disregarded
        hiveType.setInfo(structTypeInfo);
        hiveType.setObject(data);

        ContentBuilder.generate(new JacksonJsonGenerator(bos), valueWriter).value(hiveType).flush().close();

        result.set(scratchPad.bytes(), scratchPad.size());
        return result;
    }

    static Object hiveFromWritable(TypeInfo type, Writable data) {
        if (data == null || data instanceof NullWritable) {
            return null;
        }

        switch (type.getCategory()) {
        case LIST: {// or ARRAY
            ListTypeInfo listType = (ListTypeInfo) type;
            TypeInfo listElementType = listType.getListElementTypeInfo();

            ArrayWritable aw = (ArrayWritable) data;

            List<Object> list = new ArrayList<Object>();
            for (Writable writable : aw.get()) {
                list.add(hiveFromWritable(listElementType, writable));
            }

            return list;
        }
        case STRUCT: {
            StructTypeInfo structType = (StructTypeInfo) type;
            List<String> names = structType.getAllStructFieldNames();
            List<TypeInfo> info = structType.getAllStructFieldTypeInfos();

            // return just the values
            List<Object> struct = new ArrayList<Object>();

            MapWritable map = (MapWritable) data;
            Text reuse = new Text();
            for (int index = 0; index < names.size(); index++) {
                reuse.set(names.get(index));
                struct.add(hiveFromWritable(info.get(index), map.get(reuse)));
            }
            return struct;
        }

        case UNION: {
            throw new UnsupportedOperationException("union not yet supported");//break;
        }
        }
        // return as is
        return data;
    }
}