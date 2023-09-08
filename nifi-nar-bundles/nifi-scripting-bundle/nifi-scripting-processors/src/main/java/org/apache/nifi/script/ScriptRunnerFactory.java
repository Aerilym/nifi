/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.script;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processors.script.ScriptRunner;
import org.apache.nifi.script.impl.ClojureScriptRunner;
import org.apache.nifi.script.impl.GenericScriptRunner;
import org.apache.nifi.script.impl.GroovyScriptRunner;
import org.apache.nifi.script.impl.JavascriptScriptRunner;
import org.apache.nifi.script.impl.JythonScriptRunner;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

public class ScriptRunnerFactory {

    private final static ScriptRunnerFactory INSTANCE = new ScriptRunnerFactory();

    private ScriptRunnerFactory() {

    }

    public static ScriptRunnerFactory getInstance() {
        return INSTANCE;
    }

    public static Map transformMembers(Value v) {
        Map map = new HashMap();
        for (String key : v.getMemberKeys()) {
            Value member = v.getMember(key);
            if (member.hasArrayElements() && !member.isHostObject()) {
                map.put(key, transformArray(member));
            } else if (member.hasMembers() && !member.isHostObject()) {
                map.put(key, transformMembers(member));
            } else {
                map.put(key, toObject(member));
            }
        }
        return map;
    }

    public static List transformArray(Value v) {
        List list = new ArrayList();
        for (int i = 0; i < v.getArraySize(); ++i) {
            Value element = v.getArrayElement(i);
            if (element.hasArrayElements() && !element.isHostObject()) {
                list.add(transformArray(element));
            } else if (element.hasMembers() && !element.isHostObject()) {
                list.add(transformMembers(element));
            } else {
                list.add(toObject(element));
            }
        }
        return list;
    }

    public static Object toObject(Value v) {
        if (v.isNull()) {
            return null;
        } else if (v.isHostObject()) {
            return v.asHostObject();
        } else if (v.isProxyObject()) {
            return v.asProxyObject();
        } else if (v.isBoolean()) {
            return v.asBoolean();
        } else if (v.isNumber()) {
            if (v.fitsInByte()) {
                return v.asByte();
            }
            if (v.fitsInShort()) {
                return v.asShort();
            }
            if (v.fitsInInt()) {
                return v.asInt();
            }
            if (v.fitsInLong()) {
                return v.asLong();
            }
            if (v.fitsInFloat()) {
                return v.asFloat();
            }
            if (v.fitsInDouble()) {
                return v.asDouble();
            }
        } else if (v.isString()) {
            return v.asString();
        } else {
            Value value = v.getMetaObject();
            if (value.fitsInByte()) {
                return value.asByte();
            }
            if (value.fitsInShort()) {
                return value.asShort();
            }
            if (value.fitsInInt()) {
                return value.asInt();
            }
            if (value.fitsInLong()) {
                return value.asLong();
            }
            if (value.fitsInFloat()) {
                return value.asFloat();
            }
            if (value.fitsInDouble()) {
                return value.asDouble();
            }
            try {
                return value.asBoolean();
            } catch (Exception e) {
                try {
                    return value.asString();
                } catch (Exception ex) {
                    return null;
                }
            }
        }
        return null;
    }

    public static Exception transformException(Value v) {
        return v.throwException();
    }

    public ScriptRunner createScriptRunner(ScriptEngineFactory scriptEngineFactory, String scriptToRun, String[] modulePaths)
            throws ScriptException {
        ScriptEngine scriptEngine = scriptEngineFactory.getScriptEngine();
        String scriptEngineName = scriptEngineFactory.getLanguageName();
        if ("Groovy".equals(scriptEngineName)) {
            return new GroovyScriptRunner(scriptEngine, scriptToRun, null);
        }
        if ("python".equals(scriptEngineName)) {
            return new JythonScriptRunner(scriptEngine, scriptToRun, modulePaths);
        }
        if ("Clojure".equals(scriptEngineName)) {
            return new ClojureScriptRunner(scriptEngine, scriptToRun, null);
        }
        if ("ECMAScript".equals(scriptEngineName)) {
            System.err.println("Logger Is Working");
            HostAccess hostAccess = HostAccess.newBuilder(HostAccess.ALL)
                    .allowPublicAccess(true)
//                    .targetTypeMapping(
//                            Value.class, Object.class,
//                            (v) -> v.hasArrayElements(),
//                            (v) -> transformArray(v)
//                    )
                    .targetTypeMapping(
                            Value.class, List.class,
                            (v) -> v.hasArrayElements(),
                            (v) -> transformArray(v)
                    )
                    .targetTypeMapping(
                            Value.class, Collection.class,
                            (v) -> v.hasArrayElements(),
                            (v) -> transformArray(v)
                    )
                    .targetTypeMapping(
                            Value.class, Iterable.class,
                            (v) -> v.hasArrayElements(),
                            (v) -> transformArray(v)
                    )
//                    .targetTypeMapping(
//                            Value.class, Object.class,
//                            (v) -> {
//                                System.err.println("Debugging client exception");
//                                if (v.isProxyObject()){
//                                    System.err.println("Class " + v.asProxyObject().getClass().getName());
//                                }
//                                return v.isException();
//                            },
//                            (v) -> transformException(v)
//                    )
                    .build();

            ScriptEngine engine = GraalJSScriptEngine.create(null,
                    Context.newBuilder("js")
                            .allowHostAccess(hostAccess)
                            .allowHostClassLookup(s -> true)
                            .allowExperimentalOptions(true)
                            .option("js.ecmascript-version", "2022")
                            .option("js.scripting", "true")
                            .option("js.nashorn-compat", "true")
            );

            engine.put("script", scriptToRun);

            return new JavascriptScriptRunner(engine, scriptToRun, null);
        }
        return new GenericScriptRunner(scriptEngine, scriptToRun, null);
    }

    /**
     * Scans the given module paths for JARs. The path itself (whether a directory or file) will be added to the list
     * of returned module URLs, and if a directory is specified, it is scanned for JAR files (files ending with .jar).
     * Any JAR files found are added to the list of module URLs. This is a convenience method for adding directories
     * full of JAR files to an ExecuteScript or InvokeScriptedProcessor instance, rather than having to enumerate each
     * JAR's URL.
     *
     * @param modulePaths An array of module paths to scan/add
     * @param log         A logger for the calling component, to provide feedback for missing files, e.g.
     * @return An array of URLs corresponding to all modules determined from the input set of module paths.
     */
    public URL[] getModuleURLsForClasspath(String scriptEngineName, String[] modulePaths, ComponentLog log) {

        if (!"Clojure".equals(scriptEngineName)
                && !"Groovy".equals(scriptEngineName)
                && !"ECMAScript".equals(scriptEngineName)) {
            return new URL[0];
        }

        List<URL> additionalClasspath = new LinkedList<>();

        if (modulePaths == null) {
            return new URL[0];
        }
        for (String modulePathString : modulePaths) {
            File modulePath = new File(modulePathString);

            if (modulePath.exists()) {
                // Add the URL of this path
                try {
                    additionalClasspath.add(modulePath.toURI().toURL());
                } catch (MalformedURLException mue) {
                    log.warn("{} is not a valid file/folder, ignoring", new Object[]{modulePath.getAbsolutePath()}, mue);
                }

                // If the path is a directory, we need to scan for JARs and add them to the classpath
                if (!modulePath.isDirectory()) {
                    continue;
                }
                File[] jarFiles = modulePath.listFiles((dir, name) -> (name != null && name.endsWith(".jar")));

                if (jarFiles == null) {
                    continue;
                }
                // Add each to the classpath
                for (File jarFile : jarFiles) {
                    try {
                        additionalClasspath.add(jarFile.toURI().toURL());

                    } catch (MalformedURLException mue) {
                        log.warn("{} is not a valid file/folder, ignoring", new Object[]{modulePath.getAbsolutePath()}, mue);
                    }
                }
            } else {
                log.warn("{} does not exist, ignoring", modulePath.getAbsolutePath());
            }
        }
        return additionalClasspath.toArray(new URL[0]);
    }
}
