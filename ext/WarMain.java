/**
 * Copyright (c) 2010-2012 Engine Yard, Inc.
 * Copyright (c) 2007-2009 Sun Microsystems, Inc.
 * This source code is available under the MIT license.
 * See the file LICENSE.txt for details.
 */

import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.jar.JarEntry;

/**
 * Used as a Main-Class in the manifest for a .war file, so that you can run
 * a .war file with <tt>java -jar</tt>.
 *
 * WarMain can be used with different web server libraries. WarMain expects
 * to have two files present in the .war file,
 * <tt>WEB-INF/webserver.properties</tt> and <tt>WEB-INF/webserver.jar</tt>.
 *
 * When WarMain starts up, it extracts the webserver jar to a temporary
 * directory, and creates a temporary work directory for the webapp. Both
 * are deleted on exit.
 *
 * It then reads webserver.properties into a java.util.Properties object,
 * creates a URL classloader holding the jar, and loads and invokes the
 * <tt>main</tt> method of the main class mentioned in the properties.
 *
 * An example webserver.properties follows for Winstone. The <tt>args</tt>
 * property indicates the names and ordering of other properties to be used
 * as command-line arguments. The special tokens <tt>{{warfile}}</tt> and
 * <tt>{{webroot}}</tt> are substituted with the location of the .war file
 * being run and the temporary work directory, respectively.
 * <pre>
 * mainclass = winstone.Launcher
 * args = args0,args1,args2
 * args0 = --warfile={{warfile}}
 * args1 = --webroot={{webroot}}
 * args2 = --directoryListings=false
 * </pre>
 *
 * System properties can also be set via webserver.properties. For example,
 * the following entries set <tt>jetty.home</tt> before launching the server.
 * <pre>
 * props = jetty.home
 * jetty.home = {{webroot}}
 * </pre>
 */
public class WarMain extends JarMain {
    
    static final String MAIN = "/" + WarMain.class.getName().replace('.', '/') + ".class";
    static final String WEBSERVER_PROPERTIES = "/WEB-INF/webserver.properties";
    static final String WEBSERVER_JAR = "/WEB-INF/webserver.jar";
    
    // jruby arguments, consider the following command :
    //   `java -jar rails.was --1.9 -S rake db:migrate`
    // arguments == [ "--1.9" ]
    // executable == "rake"
    // executableArgv == [ "db:migrate" ]
    private final String[] arguments;
    // null to launch webserver or != null to run a executable e.g. rake
    private final String executable;
    private final String[] executableArgv;
            
    private File webroot;

    WarMain(final String[] args) {
        super(args);
        final List<String> argsList = Arrays.asList(args);
        final int sIndex = argsList.indexOf("-S");
        if ( sIndex == -1 ) {
            executable = null; executableArgv = null; arguments = null;
        }
        else {
            if ( args.length == sIndex + 1 || args[sIndex + 1].isEmpty() ) {
                throw new IllegalArgumentException("missing executable after -S");
            }
            arguments = argsList.subList(0, sIndex).toArray(new String[0]);
            executable = argsList.get(sIndex + 1);
            executableArgv = argsList.subList(sIndex + 2, argsList.size()).toArray(new String[0]);
        }
    }
    
    private URL extractWebserver() throws Exception {
        this.webroot = File.createTempFile("warbler", "webroot");
        this.webroot.delete();
        this.webroot.mkdirs();
        this.webroot = new File(this.webroot, new File(archive).getName());
        debug("webroot directory is " + this.webroot.getPath());
        InputStream jarStream = new URI("jar", entryPath(WEBSERVER_JAR), null).toURL().openStream();
        File jarFile = File.createTempFile("webserver", ".jar");
        jarFile.deleteOnExit();
        FileOutputStream outStream = new FileOutputStream(jarFile);
        try {
            byte[] buf = new byte[4096];
            int bytesRead = 0;
            while ((bytesRead = jarStream.read(buf)) != -1) {
                outStream.write(buf, 0, bytesRead);
            }
        } finally {
            jarStream.close();
            outStream.close();
        }
        debug("webserver.jar extracted to " + jarFile.getPath());
        return jarFile.toURI().toURL();
    }

    private Properties getWebserverProperties() throws Exception {
        Properties props = new Properties();
        try {
            InputStream is = getClass().getResourceAsStream(WEBSERVER_PROPERTIES);
            if ( is != null ) props.load(is);
        } catch (Exception e) { }

        for (Map.Entry entry : props.entrySet()) {
            String val = (String) entry.getValue();
            val = val.replace("{{warfile}}", archive).replace("{{webroot}}", webroot.getAbsolutePath());
            entry.setValue(val);
        }

        if (props.getProperty("props") != null) {
            String[] propsToSet = props.getProperty("props").split(",");
            for (String key : propsToSet) {
                System.setProperty(key, props.getProperty(key));
            }
        }

        return props;
    }

    private void launchWebServer(URL jar) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[] {jar});
        Thread.currentThread().setContextClassLoader(loader);
        Properties props = getWebserverProperties();
        String mainClass = props.getProperty("mainclass");
        if (mainClass == null) {
            throw new IllegalArgumentException("unknown webserver main class ("
                                               + WEBSERVER_PROPERTIES
                                               + " is missing 'mainclass' property)");
        }
        Class klass = Class.forName(mainClass, true, loader);
        Method main = klass.getDeclaredMethod("main", new Class[] { String[].class });
        String[] newArgs = launchWebServerArguments(props);
        debug("invoking webserver with: " + Arrays.deepToString(newArgs));
        main.invoke(null, new Object[] { newArgs });
    }

    private String[] launchWebServerArguments(Properties props) {
        String[] newArgs = args;

        if (props.getProperty("args") != null) {
            String[] insertArgs = props.getProperty("args").split(",");
            newArgs = new String[args.length + insertArgs.length];
            for (int i = 0; i < insertArgs.length; i++) {
                newArgs[i] = props.getProperty(insertArgs[i], "");
            }
            System.arraycopy(args, 0, newArgs, insertArgs.length, args.length);
        }

        return newArgs;
    }

    // JarMain overrides to make WarMain "launchable" 
    // e.g. java -jar rails.war -S rake db:migrate
    
    @Override
    protected String getExtractEntryPath(final JarEntry entry) {
        final String name = entry.getName();
        final String start = "WEB-INF";
        if ( name.startsWith(start) ) {
            // WEB-INF/app/controllers/application_controller.rb -> 
            // app/controllers/application_controller.rb
            return name.substring(start.length());
        }
        if ( name.indexOf('/') == -1 ) {
            // 404.html -> public/404.html
            return "/public/" + name;
        }
        return "/" + name;
    }
    
    @Override
    protected URL extractEntry(final JarEntry entry, final String path) throws Exception {
        // always extract but only return class-path entry URLs :
        final URL entryURL = super.extractEntry(entry, path);
        return path.endsWith(".jar") ? entryURL : null;
    }
    
    @Override
    protected int launchJRuby(final URL[] jars) throws Exception {
        final Object scriptingContainer = newScriptingContainer(jars);
        
        invokeMethod(scriptingContainer, "setArgv", (Object) executableArgv);
        //invokeMethod(scriptingContainer, "setHomeDirectory", "classpath:/META-INF/jruby.home");
        invokeMethod(scriptingContainer, "setCurrentDirectory", extractRoot.getAbsolutePath());
        //invokeMethod(scriptingContainer, "runScriptlet", "ENV.clear");
        //invokeMethod(scriptingContainer, "runScriptlet", "ENV['PATH']=''"); // bundler 1.1.x
        
        final Object provider = invokeMethod(scriptingContainer, "getProvider");
        final Object rubyInstanceConfig = invokeMethod(provider, "getRubyInstanceConfig");
        
        invokeMethod(rubyInstanceConfig, "setUpdateNativeENVEnabled", new Class[] { Boolean.TYPE }, false);
        
        final String executablePath = locateExecutable(scriptingContainer);
        if ( executablePath == null ) {
            throw new IllegalStateException("failed to locate gem executable: '" + executable + "'");
        }
        invokeMethod(scriptingContainer, "setScriptFilename", executablePath);
        
        invokeMethod(rubyInstanceConfig, "processArguments", (Object) arguments);
        
        Object runtime = invokeMethod(scriptingContainer, "getRuntime");
        Object executableInput =
            new SequenceInputStream(new ByteArrayInputStream(executableScriptEnvPrefix().getBytes()),
                                    (InputStream) invokeMethod(rubyInstanceConfig, "getScriptSource"));
        
        debug("invoking " + executablePath + " with: " + Arrays.toString(executableArgv));
        Object outcome = invokeMethod(runtime, "runFromMain", 
                new Class[] { InputStream.class, String.class }, 
                executableInput, executablePath 
        );
        return ( outcome instanceof Number ) ? ( (Number) outcome ).intValue() : 0;
    }
    
    protected String locateExecutable(final Object scriptingContainer) throws Exception {
        if ( executable == null ) {
            throw new IllegalStateException("no exexutable");
        }
        final File exec = new File(extractRoot, executable);
        if ( exec.exists() ) {
            return exec.getAbsolutePath();
        }
        else {
            final String script = locateExecutableScript(executable);
            return (String) invokeMethod(scriptingContainer, "runScriptlet", script);
        }
    }

    protected String executableScriptEnvPrefix() {
        final String gemsDir = new File(extractRoot, "gems").getAbsolutePath();
        final String gemfile = new File(extractRoot, "Gemfile").getAbsolutePath();
        debug("setting GEM_HOME to " + gemsDir);
        debug("... and BUNDLE_GEMFILE to " + gemfile);
        return "ENV['GEM_HOME'] = ENV['GEM_PATH'] = '"+ gemsDir +"' \n" +
        "ENV['BUNDLE_GEMFILE'] = '"+ gemfile +"' \n" + 

            // FIXME: get this from web.xml config?
            "ENV['BUNDLE_WITHOUT'] = 'assets:development:test' \n";
    }

    // TODO move this into an ERB template
    protected String locateExecutableScript(final String executable) {
        return executableScriptEnvPrefix() +
        "begin\n" +
        "  require 'META-INF/init.rb' \n" +
        // locate the executable within gemspecs :
        "  require 'rubygems' \n" +
            "  begin\n" +
            // add bundler gems to load path:
            "    require 'bundler' \n" +
            // TODO: environment from web.xml. Any others?
            "    Bundler.setup(:default, ENV.values_at('RACK_ENV', 'RAILS_ENV').compact)\n" +
            "  rescue LoadError\n" +
            // bundler not used
            "  end\n" +
        "  exec = '"+ executable +"' \n" +
        "  spec = Gem::Specification.find { |s| s.executables.include?(exec) } \n" +
        "  spec ? spec.bin_file(exec) : nil \n" +
        // returns the full path to the executable
        "rescue SystemExit => e\n" +
        "  e.status\n" +
        "end";
    }
    
    @Override
    protected int start() throws Exception {
        if ( executable == null ) {
            try {
                URL server = extractWebserver();
                launchWebServer(server);
            }
            catch (FileNotFoundException e) {
                if ( e.getMessage().indexOf("WEB-INF/webserver.jar") > -1 ) {
                    System.out.println("specify the -S argument followed by the bin file to run e.g. `java -jar rails.war -S rake -T` ...");
                    System.out.println("(or if you'd like your .war file to start a web server package it using `warbler executable war`)");
                }
                throw e;
            }
            return 0;
        }
        else {
            return super.start();
        }
    }

    @Override
    public void run() {
        super.run();
        if ( webroot != null ) delete(webroot.getParentFile());
    }

    public static void main(String[] args) {
        doStart(new WarMain(args));
    }

}
