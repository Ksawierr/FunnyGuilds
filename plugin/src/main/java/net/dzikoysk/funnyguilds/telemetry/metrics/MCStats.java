package net.dzikoysk.funnyguilds.telemetry.metrics;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;
import net.dzikoysk.funnyguilds.FunnyGuilds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import panda.std.stream.PandaStream;

public class MCStats {

    private static final int REVISION = 7;
    private static final String BASE_URL = "http://report.mcstats.org";
    private static final String REPORT_URL = "/plugin/%s";
    private static final int PING_INTERVAL = 10;
    private final Plugin plugin;
    private final Set<Graph> graphs = Collections.synchronizedSet(new HashSet<>());
    private final YamlConfiguration configuration;
    private final File configurationFile;
    private final String guid;
    private final boolean debug;
    private final Object optOutLock = new Object();
    private volatile BukkitTask task;

    public MCStats(Plugin plugin) throws IOException {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        this.plugin = plugin;

        // load the config
        this.configurationFile = this.getConfigFile();
        this.configuration = YamlConfiguration.loadConfiguration(this.configurationFile);

        // add some defaults
        this.configuration.addDefault("opt-out", false);
        this.configuration.addDefault("guid", UUID.randomUUID().toString());
        this.configuration.addDefault("debug", false);

        // Do we need to create the file?
        if (this.configuration.get("guid", null) == null) {
            this.configuration.options().header("http://mcstats.org").copyDefaults(true);
            this.configuration.save(this.configurationFile);
        }

        // Load the guid then
        this.guid = this.configuration.getString("guid");
        this.debug = this.configuration.getBoolean("debug", false);
    }

    public static byte[] gzip(String input) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
            gzipOutputStream.write(input.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException exception) {
            FunnyGuilds.getPluginLogger().error("MCStats error", exception);
        }

        return byteArrayOutputStream.toByteArray();
    }

    private static void appendJSONPair(StringBuilder json, String key, String value) {
        boolean isValueNumeric = false;

        try {
            if (value.equals("0") || !value.endsWith("0")) {
                Double.parseDouble(value);
                isValueNumeric = true;
            }
        }
        catch (NumberFormatException e) {
            FunnyGuilds.getPluginLogger().debug("[MCStats] Value isn't numeric.");
        }

        if (json.charAt(json.length() - 1) != '{') {
            json.append(',');
        }

        json.append(escapeJSON(key));
        json.append(':');

        if (isValueNumeric) {
            json.append(value);
        }
        else {
            json.append(escapeJSON(value));
        }
    }

    private static String escapeJSON(String text) {
        StringBuilder builder = new StringBuilder();

        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char chr = text.charAt(index);

            switch (chr) {
                case '"':
                case '\\':
                    builder.append('\\');
                    builder.append(chr);
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                default:
                    if (chr < ' ') {
                        String t = "000" + Integer.toHexString(chr);
                        builder.append("\\u").append(t.substring(t.length() - 4));
                    }
                    else {
                        builder.append(chr);
                    }

                    break;
            }
        }

        builder.append('"');
        return builder.toString();
    }

    private static String urlEncode(String text) throws UnsupportedEncodingException {
        return URLEncoder.encode(text, "UTF-8");
    }

    public Graph createGraph(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Graph name cannot be null");
        }

        // Construct the graph object
        Graph graph = new Graph(name);

        // Now we can add our graph
        this.graphs.add(graph);

        // and return
        return graph;
    }

    public void addGraph(Graph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        this.graphs.add(graph);
    }

    public boolean start() {
        synchronized (this.optOutLock) {
            // Did we opt out?
            if (this.isOptOut()) {
                return false;
            }

            // Is metrics already running?
            if (this.task != null) {
                return true;
            }

            // Begin hitting the server with glorious data
            this.task = this.plugin.getServer().getScheduler().runTaskTimerAsynchronously(this.plugin, new Runnable() {
                private boolean firstPost = true;

                public void run() {
                    try {
                        // This has to be synchronized, or it can collide with the disable method
                        synchronized (MCStats.this.optOutLock) {
                            // Disable Task, if it is running and the server owner decided to opt-out
                            if (MCStats.this.isOptOut() && MCStats.this.task != null) {
                                MCStats.this.task.cancel();
                                MCStats.this.task = null;

                                // Tell all plotters to stop gathering information
                                PandaStream.of(MCStats.this.graphs).forEach(Graph::onOptOut);
                            }
                        }

                        // We use the inverse of firstPost because if it is the first time we are posting,
                        // it is not an interval ping, so it evaluates to FALSE
                        // Each time thereafter it will evaluate to TRUE, i.e. PING!
                        MCStats.this.postPlugin(!this.firstPost);

                        // After the first post we set firstPost to false
                        // Each post thereafter will be a ping
                        this.firstPost = false;
                    }
                    catch (IOException e) {
                        if (MCStats.this.debug) {
                            Bukkit.getLogger().log(Level.INFO, "[Metrics] " + e.getMessage());
                        }
                    }
                }
            }, 0, PING_INTERVAL * 1200);

            return true;
        }
    }

    public void enable() throws IOException {
        // This has to be synchronized, or it can collide with the check in the task.
        synchronized (this.optOutLock) {
            // Check if the server owner has already set opt-out, if not, set it.
            if (this.isOptOut()) {
                this.configuration.set("opt-out", false);
                this.configuration.save(this.configurationFile);
            }

            // Enable Task, if it is not running
            if (this.task == null) {
                this.start();
            }
        }
    }

    public void disable() throws IOException {
        // This has to be synchronized, or it can collide with the check in the task.
        synchronized (this.optOutLock) {
            // Check if the server owner has already set opt-out, if not, set it.
            if (!this.isOptOut()) {
                this.configuration.set("opt-out", true);
                this.configuration.save(this.configurationFile);
            }

            // Disable Task, if it is running
            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }
        }
    }

    private void postPlugin(boolean isPing) throws IOException {
        // Server software specific section
        String pluginName = "FunnyGuilds";
        boolean onlineMode = Bukkit.getServer().getOnlineMode(); // TRUE if online mode is enabled
        String pluginVersion = FunnyGuilds.getInstance().getVersion().getMainVersion();
        String serverVersion = Bukkit.getVersion();
        int playersOnline = Bukkit.getServer().getOnlinePlayers().size();

        // END server software specific section -- all code below does not use any code outside this class / Java

        // Construct the post data
        StringBuilder json = new StringBuilder(1024);
        json.append('{');

        // The plugin's description file containing all the plugin data such as name, version, author, etc
        appendJSONPair(json, "guid", this.guid);
        appendJSONPair(json, "plugin_version", pluginVersion);
        appendJSONPair(json, "server_version", serverVersion);
        appendJSONPair(json, "players_online", Integer.toString(playersOnline));

        // New data as of R6
        String osname = System.getProperty("os.name");
        String osarch = System.getProperty("os.arch");
        String osversion = System.getProperty("os.version");
        String java_version = System.getProperty("java.version");
        int coreCount = Runtime.getRuntime().availableProcessors();

        // normalize os arch .. amd64 -> x86_64
        if (osarch.equals("amd64")) {
            osarch = "x86_64";
        }

        appendJSONPair(json, "osname", osname);
        appendJSONPair(json, "osarch", osarch);
        appendJSONPair(json, "osversion", osversion);
        appendJSONPair(json, "cores", Integer.toString(coreCount));
        appendJSONPair(json, "auth_mode", onlineMode ? "1" : "0");
        appendJSONPair(json, "java_version", java_version);

        // If we're pinging, append it
        if (isPing) {
            appendJSONPair(json, "ping", "1");
        }

        if (!this.graphs.isEmpty()) {
            synchronized (this.graphs) {
                json.append(',');
                json.append('"');
                json.append("graphs");
                json.append('"');
                json.append(':');
                json.append('{');

                boolean firstGraph = true;

                for (Graph graph : this.graphs) {
                    StringBuilder graphJson = new StringBuilder();
                    graphJson.append('{');

                    for (Plotter plotter : graph.getPlotters()) {
                        appendJSONPair(graphJson, plotter.getColumnName(), Integer.toString(plotter.getValue()));
                    }

                    graphJson.append('}');

                    if (!firstGraph) {
                        json.append(',');
                    }

                    json.append(escapeJSON(graph.getName()));
                    json.append(':');
                    json.append(graphJson);

                    firstGraph = false;
                }

                json.append('}');
            }
        }

        // close json
        json.append('}');

        // Create the url
        URL url = new URL(BASE_URL + String.format(REPORT_URL, urlEncode(pluginName)));

        // Connect to the website
        URLConnection connection;

        // Mineshafter creates a socks proxy, so we can safely bypass it
        // It does not reroute POST requests, so we need to go around it
        if (isMineshafterPresent()) {
            connection = url.openConnection(Proxy.NO_PROXY);
        }
        else {
            connection = url.openConnection();
        }


        byte[] uncompressed = json.toString().getBytes();
        byte[] compressed = gzip(json.toString());

        // Headers
        connection.addRequestProperty("User-Agent", "MCStats/" + REVISION);
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("Content-Encoding", "gzip");
        connection.addRequestProperty("Content-Length", Integer.toString(compressed.length));
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");

        connection.setDoOutput(true);

        if (this.debug) {
            System.out.println("[Metrics] Prepared request for " + pluginName + " uncompressed=" +
                    uncompressed.length + " compressed=" + compressed.length);
        }

        // Write the data
        OutputStream os = connection.getOutputStream();
        os.write(compressed);
        os.flush();

        // Now read the response
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String response = reader.readLine();

        // close resources
        os.close();
        reader.close();

        if (response == null || response.startsWith("ERR") || response.startsWith("7")) {
            if (response == null) {
                response = "null";
            }
            else if (response.startsWith("7")) {
                response = response.substring(response.startsWith("7,") ? 2 : 1);
            }

            throw new IOException(response);
        }
        else {
            // Is this the first update this hour?
            if (response.equals("1") || response.contains("This is your first update this hour")) {
                synchronized (this.graphs) {

                    for (Graph graph : this.graphs) {
                        for (Plotter plotter : graph.getPlotters()) {
                            plotter.reset();
                        }
                    }
                }
            }
        }
    }

    public boolean isOptOut() {
        synchronized (this.optOutLock) {
            try {
                // Reload the metrics file
                this.configuration.load(this.getConfigFile());
            }
            catch (IOException | InvalidConfigurationException ex) {
                if (this.debug) {
                    Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                }
                return true;
            }
            return this.configuration.getBoolean("opt-out", false);
        }
    }

    public File getConfigFile() {
        // I believe the easiest way to get the base folder (e.g craftbukkit set via -P) for plugins to use
        // is to abuse the plugin object we already have
        // plugin.getDataFolder() => base/plugins/PluginA/
        // pluginsFolder => base/plugins/
        // The base is not necessarily relative to the startup directory.
        File pluginsFolder = this.plugin.getDataFolder().getParentFile();

        // return => base/plugins/PluginMetrics/config.yml
        return new File(new File(pluginsFolder, "PluginMetrics"), "config.yml");
    }

    private static boolean isMineshafterPresent() {
        try {
            Class.forName("mineshafter.MineServer");
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static final class Graph {

        private final String name;
        private final Set<Plotter> plotters = new LinkedHashSet<>();

        private Graph(String name) {
            this.name = name;
        }

        public void addPlotter(Plotter plotter) {
            this.plotters.add(plotter);
        }

        public void removePlotter(Plotter plotter) {
            this.plotters.remove(plotter);
        }

        private void onOptOut() {
        }

        public String getName() {
            return this.name;
        }

        public Set<Plotter> getPlotters() {
            return Collections.unmodifiableSet(this.plotters);
        }

        @Override
        public int hashCode() {
            return this.name.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Graph)) {
                return false;
            }

            Graph graph = (Graph) object;
            return graph.name.equals(this.name);
        }

    }

    public abstract static class Plotter {

        private final String name;

        public Plotter() {
            this("Default");
        }

        public Plotter(String name) {
            this.name = name;
        }

        public void reset() {
        }

        public abstract int getValue();

        public String getColumnName() {
            return this.name;
        }

        @Override
        public int hashCode() {
            return this.name.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Plotter)) {
                return false;
            }

            Plotter plotter = (Plotter) object;
            return plotter.name.equals(this.name) && plotter.getValue() == this.getValue();
        }

    }

}