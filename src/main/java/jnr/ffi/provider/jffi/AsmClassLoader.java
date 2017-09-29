/*
 * Copyright (C) 2008-2010 Wayne Meissner
 *
 * This file is part of the JNR project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jnr.ffi.provider.jffi;

import com.android.dex.DexFormat;
import com.android.dx.cf.direct.DirectClassFile;
import com.android.dx.cf.direct.StdAttributeFactory;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.cf.CfOptions;
import com.android.dx.dex.cf.CfTranslator;
import com.android.dx.dex.file.ClassDefItem;
import com.android.dx.dex.file.DexFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class AsmClassLoader extends ClassLoader {
    private final ConcurrentMap<String, Class> definedClasses = new ConcurrentHashMap<String, Class>();

    public AsmClassLoader() {
    }

    public AsmClassLoader(ClassLoader parent) {
        super(parent);
    }


    public Class defineClass(String name, byte[] b) {
        Class klass;
        try {
            klass = translateToDexAndLoad(name, b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        definedClasses.putIfAbsent(name, klass);
        resolveClass(klass);
        return klass;
    }

    private Class translateToDexAndLoad(String name, byte[] bytes) throws IOException {
        // Yeah, that's right: we gonna take those .class file bytes and feed them into DX to get classes.dex.
        // After that we gonna dump this dex into a brand new jar file and then add it to classpath via DexClassLoader.
        // This is necessary because it is not supported to load .dex straight in memory.

        File dexCache;
        String property = System.getProperty("jnrffi.dexcache");
        if (property != null) {
            dexCache = new File(property);
        } else {
            throw new RuntimeException();
        }

        // Create DirectClassFile. It seems it's ok that filePath doesn't exists.
        DirectClassFile directClassFile = new DirectClassFile(
                bytes, CodegenUtils.p(name) + ".class", false);
        directClassFile.setAttributeFactory(StdAttributeFactory.THE_ONE);
        CfOptions cfOptions = new CfOptions();

        // Create DexFile. Unlike .class file it might contain many class declarations.
        DexOptions dexOptions = new DexOptions();
        dexOptions.targetApiLevel = DexFormat.API_NO_EXTENDED_OPCODES;
        DexFile dexFile = new DexFile(dexOptions);

        // Translate .class bytecode into ClassDefItem and add it to the dex file.
        ClassDefItem classDefItem = CfTranslator.translate(directClassFile, bytes, cfOptions, dexOptions, dexFile);
        dexFile.add(classDefItem);

        // Serialize .dex into byte buffer.
        byte[] dexBytes = dexFile.toDex(null, false);

        // Create temporary .jar and put `classes.dex` inside it.
        File dexJarFile = File.createTempFile("dexproxy", ".jar", dexCache);
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(dexJarFile));
        JarEntry entry = new JarEntry(DexFormat.DEX_IN_JAR_NAME);
        entry.setSize(dexBytes.length);
        jarOut.putNextEntry(entry);
        jarOut.write(dexBytes);
        jarOut.closeEntry();
        jarOut.close();


        // Load this .jar into DexClassLoader.
        ClassLoader classLoader = generateClassLoader(dexJarFile, dexCache, this);
        try {
            return classLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static ClassLoader generateClassLoader(File result, File dexCache, ClassLoader parent) {
        try {
            return (ClassLoader) Class.forName("dalvik.system.DexClassLoader")
                    .getConstructor(String.class, String.class, String.class, ClassLoader.class)
                    .newInstance(result.getPath(), dexCache.getAbsolutePath(), null, parent);
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("load() requires a Dalvik VM", e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (InstantiationException e) {
            throw new AssertionError();
        } catch (NoSuchMethodException e) {
            throw new AssertionError();
        } catch (IllegalAccessException e) {
            throw new AssertionError();
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class klass = definedClasses.get(name);
        if (klass != null) {
            return klass;
        }
        return super.findClass(name);
    }
}
