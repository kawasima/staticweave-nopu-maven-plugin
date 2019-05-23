package net.unit8.maven.plugins;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.persistence.config.PersistenceUnitProperties;
import org.eclipse.persistence.internal.jpa.EntityManagerSetupImpl;
import org.eclipse.persistence.internal.jpa.StaticWeaveInfo;
import org.eclipse.persistence.internal.jpa.deployment.ArchiveFactoryImpl;
import org.eclipse.persistence.internal.jpa.deployment.PersistenceUnitProcessor;
import org.eclipse.persistence.internal.jpa.deployment.SEPersistenceUnitInfo;
import org.eclipse.persistence.internal.jpa.weaving.StaticWeaveDirectoryOutputHandler;
import org.eclipse.persistence.jpa.Archive;
import org.eclipse.persistence.logging.AbstractSessionLog;
import org.eclipse.persistence.logging.SessionLog;

import javax.persistence.spi.ClassTransformer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.stream.Collectors;

import static org.eclipse.persistence.tools.weaving.jpa.StaticWeaveProcessor.getDirectoryFromEntryName;

@Mojo(name = "weave",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class WeaveMojo extends AbstractMojo {
    @Parameter(property = "project.build.outputDirectory")
    protected String source;

    @Parameter(property = "project.build.outputDirectory")
    protected String target;

    @Parameter(required = true)
    protected List<String> packages;

    @Parameter(property = "weave.logLevel", defaultValue = "ALL")
    private String logLevel;

    private static final int NUMBER_OF_BYTES = 1024;
    private ClassLoader classLoader;
    private List<ClassTransformer> classTransformers = new ArrayList<>();

    @Component
    private MavenProject project;

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            List<URL> classpath = buildClassPath();
            classpath.add(0, new File(source).toURI().toURL());
            if (!classpath.isEmpty()) {
                classLoader = new URLClassLoader(
                        classpath.toArray(new URL[]{}), Thread.currentThread()
                        .getContextClassLoader());
            } else {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            weave();
        } catch (IOException | URISyntaxException e) {
            throw new MojoExecutionException("The error occurs at weaving", e);
        }
    }

    protected void weave() throws IOException, URISyntaxException {
        URL sourceUrl = new File(source).toURI().toURL();
        URL targetUrl = new File(target).toURI().toURL();

        StaticWeaveInfo info = new StaticWeaveInfo(new LogWriter(getLog()), getLogLevel());
        SEPersistenceUnitInfo unitInfo = createPersistenceUnitInfo();
        Map emptyMap = new HashMap(0);
        //build class transformer.
        String puName = unitInfo.getPersistenceUnitName();
        String sessionName = (String)unitInfo.getProperties().get(PersistenceUnitProperties.SESSION_NAME);
        if (sessionName == null) {
            sessionName = puName;
        }
        EntityManagerSetupImpl emSetupImpl = new EntityManagerSetupImpl(puName, sessionName);
        //indicates that predeploy is used for static weaving, also passes logging parameters
        emSetupImpl.setStaticWeaveInfo(info);
        ClassTransformer transformer = emSetupImpl.predeploy(unitInfo, emptyMap);
        classTransformers.add(transformer);

        StaticWeaveDirectoryOutputHandler swoh = new StaticWeaveDirectoryOutputHandler(sourceUrl, targetUrl);
        Archive sourceArchive =(new ArchiveFactoryImpl()).createArchive(sourceUrl, null, null);
        if (sourceArchive != null) {
            try {
                Iterator entries = sourceArchive.getEntries();
                while (entries.hasNext()){
                    String entryName = (String)entries.next();
                    InputStream entryInputStream = sourceArchive.getEntry(entryName);

                    // Add a directory entry
                    swoh.addDirEntry(getDirectoryFromEntryName(entryName));

                    // Add a regular entry
                    JarEntry newEntry = new JarEntry(entryName);

                    // Ignore non-class files.
                    if (!entryName.endsWith(".class") || "module-info.class".equals(entryName)) {
                        swoh.addEntry(entryInputStream, newEntry);
                        continue;
                    }

                    String className = PersistenceUnitProcessor.buildClassNameFromEntryString(entryName) ;
                    byte[] originalClassBytes;
                    byte[] transferredClassBytes;
                    try {
                        Class thisClass = classLoader.loadClass(className);
                        // If the class is not in the classpath, we simply copy the entry
                        // to the target(no weaving).
                        if (thisClass == null){
                            swoh.addEntry(entryInputStream, newEntry);
                            continue;
                        }

                        // Try to read the loaded class bytes, the class bytes is required for
                        // classtransformer to perform transfer. Simply copy entry to the target(no weaving)
                        // if the class bytes can't be read.
                        InputStream is = classLoader.getResourceAsStream(entryName);
                        if (is!=null){
                            ByteArrayOutputStream baos = null;
                            try{
                                baos = new ByteArrayOutputStream();
                                byte[] bytes = new byte[NUMBER_OF_BYTES];
                                int bytesRead = is.read(bytes, 0, NUMBER_OF_BYTES);
                                while (bytesRead >= 0){
                                    baos.write(bytes, 0, bytesRead);
                                    bytesRead = is.read(bytes, 0, NUMBER_OF_BYTES);
                                }
                                originalClassBytes = baos.toByteArray();
                            } finally {
                                Objects.requireNonNull(baos).close();
                                is.close();
                            }
                        } else {
                            swoh.addEntry(entryInputStream, newEntry);
                            continue;
                        }

                        // If everything is OK so far, we perform the weaving. we need three parameters in order to
                        // class to perform weaving for that class, the class name,the class object and class bytes.
                        transferredClassBytes = transform(className.replace('.', '/'), thisClass, originalClassBytes);

                        // If transferredClassBytes is null means the class dose not get woven.
                        if (transferredClassBytes!=null){
                            swoh.addEntry(newEntry, transferredClassBytes);
                        } else {
                            swoh.addEntry(entryInputStream, newEntry);
                        }
                    } catch (IllegalClassFormatException | ClassNotFoundException e) {
                        AbstractSessionLog.getLog().logThrowable(AbstractSessionLog.WARNING, AbstractSessionLog.WEAVER, e);
                        // Anything went wrong, we need log a warning message, copy the entry to the target and
                        // process next entry.
                        swoh.addEntry(entryInputStream, newEntry);
                    } finally {
                        // Need close the inputstream for current entry before processing next one.
                        entryInputStream.close();
                    }
                }
            } finally {
                sourceArchive.close();
                swoh.closeOutputStream();
            }
        }

    }
    byte[] transform(String originalClassName, Class originalClass, byte[] originalClassBytes)throws IllegalClassFormatException {
        byte[] newClassBytes = null;
        for(ClassTransformer transformer : classTransformers){
            newClassBytes=transformer.transform(classLoader, originalClassName, originalClass, null, originalClassBytes);
            if(newClassBytes!=null) {
                break;
            }
        }
        return newClassBytes;
    }

    SEPersistenceUnitInfo createPersistenceUnitInfo() {
        SEPersistenceUnitInfo pu = new SEPersistenceUnitInfo();
        pu.setPersistenceUnitName("for-weaving");
        pu.setClassLoader(classLoader);
        pu.setPersistenceUnitRootUrl(getClass().getResource("/"));
        ManagedClassScanner managedClassScanner = new ManagedClassScanner(classLoader, getLog());
        final List<Class<?>> managedClasses = managedClassScanner.scan(packages);
        List<URL> jarFiles = managedClasses.stream()
                .map(cls -> cls.getResource("/"))
                .distinct()
                .collect(Collectors.toList());
        pu.setJarFileUrls(jarFiles);

        List<String> managedClassNames = managedClasses.stream()
                .map(Class::getName)
                .collect(Collectors.toList());
        getLog().info("Entity:" + managedClasses);
        pu.setManagedClassNames(managedClassNames);
        pu.setExcludeUnlistedClasses(false);
        return pu;
    }

    private List<URL> buildClassPath() throws MalformedURLException {
        List<URL> urls = new ArrayList<>();

        if (project == null) {

            getLog().error(
                    "MavenProject is empty, unable to build ClassPath. No Models can be woven.");

        } else {
            Set<Artifact> artifacts = project.getArtifacts();
            for (Artifact a : artifacts) {
                urls.add(a.getFile().toURI().toURL());
            }

        }

        return urls;
    }
    public void setLogLevel(String logLevel) {
        if (SessionLog.OFF_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.SEVERE_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.WARNING_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.INFO_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.CONFIG_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.FINE_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.FINER_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.FINEST_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.ALL_LABEL.equalsIgnoreCase(logLevel)) {
            this.logLevel = logLevel.toUpperCase();
        } else {
            getLog().error(
                    "Unknown log level: " + logLevel
                            + " default LogLevel is used.");
        }
    }

    private int getLogLevel() {
        return AbstractSessionLog.translateStringToLoggingLevel(logLevel);
    }
}
