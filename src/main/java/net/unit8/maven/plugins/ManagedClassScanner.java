package net.unit8.maven.plugins;

import org.apache.maven.plugin.logging.Log;

import javax.persistence.Entity;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

public class ManagedClassScanner {
    private final ClassLoader loader;
    private final Log logger;

    public ManagedClassScanner(ClassLoader loader, Log logger) {
        this.loader = loader;
        this.logger = logger;
    }

    public List<Class<?>> scan(List<String> packages) {
        return packages.stream()
                .flatMap(p -> scanPackage(p).stream())
                .distinct()
                .collect(Collectors.toList());
    }

    public List<Class<?>> scanPackage(String aPackage) throws UncheckedIOException {
        List<Class<?>> managedClasses = new ArrayList<>();
        try {
            Enumeration<URL> resources = loader.getResources(aPackage.replace('.', '/'));
            List<File> dirs = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                dirs.add(new File(resource.getFile()));
            }

            for (File directory : dirs) {
                try {
                    managedClasses.addAll(findClasses(directory, aPackage)
                            .stream()
                            .filter(c -> c.getAnnotation(Entity.class) != null)
                            .collect(Collectors.toList())
                    );
                } catch (ClassNotFoundException ignore) {

                }
            }
            return managedClasses;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Class<?>> findClasses(File directory, String packageName) throws ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        if (!directory.exists()) {
            return classes;
        }
        File[] files = directory.listFiles();
        if (files == null) return classes;

        for (File file : files) {
            if (file.isDirectory()) {
                assert !file.getName().contains(".");
                classes.addAll(findClasses(file, packageName + "." + file.getName()));
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                classes.add(Class.forName(className, true, loader));
            }
        }
        return classes;
    }

}
