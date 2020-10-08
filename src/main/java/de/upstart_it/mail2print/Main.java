package de.upstart_it.mail2print;

import com.sun.mail.imap.IMAPFolder;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.activation.DataSource;
import javax.mail.Flags;
import javax.mail.Flags.Flag;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeMessage;
import javax.mail.search.FlagTerm;
import javax.print.PrintException;
import javax.print.PrintService;
import lombok.extern.java.Log;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.mail.util.MimeMessageParser;
import org.pf4j.DefaultPluginManager;
import org.pf4j.ExtensionFactory;
import org.pf4j.PluginManager;
import org.pf4j.SingletonExtensionFactory;

/**
 *
 * @author Thomas Oster <thomas.oster@upstart-it.de>
 */
@Log
public class Main {

    private final Store store;
    private final ExecutorService es;
    private final PrintHelper printHelper = new PrintHelper();
    private final PluginManager pluginManager;
    private final Session session;

    private PrintService printer = null;
    private boolean idleMode = false;
    private boolean deleteMails = false;
    private boolean keepRunning = true;
    private String user = "";
    private String password = "";
    private String hostname = "";
    private File output = null;

    private void parseCommandLine(String[] args) throws ParseException {
        Options options = new Options();

        Option oPrinter = new Option("p", "printer", true, "Printer to use. If not specified, files won't be printed");
        options.addOption(oPrinter);

        Option oOutput = new Option("o", "output-folder", true, "Folder where to save attachments.");
        options.addOption(oOutput);

        Option oConvert = new Option("c", "convert-office-files", false, "Use Libre/Open Office to convert Office Documents");
        options.addOption(oConvert);

        Option oIdle = new Option("i", "idle-mode", false, "Use IMAP-IDLE to wait for new messages");
        options.addOption(oIdle);

        Option oDelete = new Option("d", "delete", false, "Delete mails after successful procesing");
        options.addOption(oDelete);

        Option oUser = new Option("u", "username", true, "Username for the IMAP account");
        oUser.setRequired(true);
        options.addOption(oUser);

        Option oPassword = new Option("P", "password", true, "Password for the IMAP account");
        oPassword.setRequired(true);
        options.addOption(oPassword);

        Option oHost = new Option("h", "host", true, "IMAP server");
        oHost.setRequired(true);
        options.addOption(oHost);

        CommandLineParser parser = new GnuParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("mail2print", options);
            throw e;
        }

        if (cmd.hasOption(oPrinter.getLongOpt())) {
            printer = printHelper.getPrinter(cmd.getOptionValue(oPrinter.getLongOpt()));
            if (printer == null) {
                throw new ParseException("Printer " + cmd.getOptionValue(oPrinter.getLongOpt()) + " not found");
            }
        }
        if (cmd.hasOption(oOutput.getLongOpt())) {
            output = new File(cmd.getOptionValue(oOutput.getLongOpt()));
            if (!output.isDirectory() || !output.canWrite()) {
                throw new ParseException("Folder " + oOutput.getLongOpt() + " does not exist or is not writeable");
            }
        }
        idleMode = cmd.hasOption(oIdle.getLongOpt());
        deleteMails = cmd.hasOption(oDelete.getLongOpt());
        user = cmd.getOptionValue(oUser.getLongOpt());
        password = cmd.getOptionValue(oPassword.getLongOpt(), "Virtual_PDF_Printer");
        hostname = cmd.getOptionValue(oHost.getLongOpt(), "Virtual_PDF_Printer");
    }

    private Stream<ConverterPlugin> getConverter() {
        return pluginManager.getExtensions(ConverterPlugin.class).stream().filter(Objects::nonNull);
    }

    public Main(String[] args) throws NoSuchProviderException, MessagingException, IOException, ParseException {
        parseCommandLine(args);

        es = Executors.newCachedThreadPool();
        Properties props = System.getProperties();
        props.setProperty("mail.imaps.timeout", "10000");
        props.setProperty("mail.imaps.ssl.trust", "*");
        // Get a Session object
        session = Session.getInstance(props, null);
        // Get a Store object
        store = session.getStore("imaps");

        pluginManager = new DefaultPluginManager() {
            @Override
            protected ExtensionFactory createExtensionFactory() {
                return new SingletonExtensionFactory();
            }
        };
        Logger.getGlobal().info("loading Plugins...");
        pluginManager.loadPlugins();
        pluginManager.startPlugins();
        getConverter().forEach((plugin) -> {
            Logger.getGlobal().log(Level.INFO, "Plugin {0} loaded", plugin.getName());
        });
    }

    private void shutdown() throws MessagingException {
        getConverter().forEach(ConverterPlugin::shutdown);
        pluginManager.stopPlugins();
        store.close();
        keepRunning = false;
        es.shutdownNow();
    }

    private List<DataSource> getAttachments(MimeMessage msg) throws Exception {
        MimeMessageParser parser = new MimeMessageParser(msg);
        parser.parse();
        return parser.getAttachmentList();
    }

    private boolean print(String subject, final DataSource ds) throws IOException, PrintException {

        try {
            String contentType = ds.getContentType().toLowerCase();
            String filename = ds.getName().toLowerCase();
            byte[] data = null;
            if (filename.endsWith(".pdf") || contentType.contains("application/pdf")) {
                Logger.getGlobal().log(Level.FINE, "Printing {0} with type {1}", new Object[]{ds.getName(), ds.getContentType()});
                data = IOUtils.toByteArray(ds.getInputStream());
            } else {
                for (ConverterPlugin plugin : getConverter().toArray(ConverterPlugin[]::new)) {
                    if (plugin.canConvertFile(contentType, filename, subject)) {
                        try {
                            Logger.getGlobal().log(Level.INFO, "Using {0} to convert {1}", new Object[]{plugin.getName(), filename});
                            data = plugin.convertToPdf(ds.getInputStream(), contentType, filename, subject);
                            break;
                        } catch (Exception e) {
                            Logger.getGlobal().severe(e.getLocalizedMessage());
                        }
                    }
                }
                if (data == null) {
                    Logger.getGlobal().log(Level.INFO, "Skipping unsupported {0} with type {1}", new Object[]{ds.getName(), ds.getContentType()});
                    return false;
                }
            }
            printHelper.printPDF(printer, data, null, null);
        } catch (Exception e) {
            Logger.getGlobal().log(Level.WARNING, "General printing error");
        }

        return true;
    }

    private void process(Message msg) throws Exception {
        Logger.getGlobal().log(Level.FINE, "processing {0}", msg.getSubject());
        boolean processed = false;
        for (DataSource e : getAttachments((MimeMessage) msg)) {
            if (output != null) {
                int number = 0;
                String name = e.getName();
                if (name == null) {
                    name = "unknown";
                }
                File target = new File(output, name);
                while (target.exists()) {
                    target = new File(output, (++number) + name);
                }
                FileUtils.writeByteArrayToFile(target, IOUtils.toByteArray(e.getInputStream()));
                e.getInputStream().reset();
                processed = true;
            }
            if (printer != null) {
                if (print(msg.getSubject(), e)) {
                    processed = true;
                }
            }
        }
        if (processed && deleteMails) {
            msg.setFlag(Flag.DELETED, true);
        }
    }

    private void processUnreadMessages(Folder folder) throws MessagingException, Exception {
        // Fetch unseen messages from inbox folder
        Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        Logger.getGlobal().log(Level.INFO, "Processing {0} unread messages...", messages.length);
        // Sort messages from recent to oldest
        Arrays.sort(messages, (m1, m2) -> {
            try {
                return m2.getSentDate().compareTo(m1.getSentDate());
            } catch (MessagingException e) {
                throw new RuntimeException(e);
            }
        });
        for (Message msg : messages) {
            process(msg);
        }
        if (deleteMails) {
            try {
                folder.expunge();
            } catch (MessagingException ex) {
                Logger.getGlobal().log(Level.SEVERE, null, ex);
            }
        }
    }

    private void run() throws MessagingException, Exception {
        // Connect
        Logger.getGlobal().log(Level.INFO, "connecting to {0}", hostname);
        store.connect(hostname, user, password);

        IMAPFolder folder = (IMAPFolder) store.getFolder("INBOX");
        folder.open(Folder.READ_WRITE);
        Thread keepAliveThread = new Thread() {
            @Override
            public void run() {
                Logger.getGlobal().fine("keep alive started...");
                while (keepRunning && !Thread.interrupted()) {
                    try {
                        Thread.sleep(120000);//2min
                        Logger.getGlobal().fine("Checking if connection is alive...");
                        folder.getNewMessageCount();
                    } catch (InterruptedException e) {
                        Logger.getGlobal().fine("Stopping keep alive");
                        // Ignore, just aborting the thread...
                    } catch (MessagingException ex) {
                        Logger.getGlobal().log(Level.SEVERE, null, ex);
                        
                    }
                }
            }
        };
        if (idleMode) {
            keepAliveThread.start();
        }
        while (!Thread.interrupted()) {
            if (!folder.isOpen()) {
                Logger.getGlobal().info("Connection was lost, reestablish....");
                folder.open(Folder.READ_WRITE);
            }
            processUnreadMessages(folder);
            if (!idleMode) {
                break;
            }
            Logger.getGlobal().info("Waiting for new messages (IDLE)...");
            try {
                folder.idle(true);
            }
            catch (Exception e) {
                Logger.getGlobal().info("Idle Exception "+e.getLocalizedMessage());
            }
        }
        if (idleMode && keepAliveThread.isAlive()) {
            keepAliveThread.interrupt();
            keepAliveThread.join();
        }
        if (folder.isOpen()) {
            folder.close();
        }
        shutdown();
    }

    public static void main(String[] args) {
        Main instance = null;
        try {
            instance = new Main(args);
            instance.run();
        } catch (MessagingException ex) {
            Logger.getGlobal().log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getGlobal().log(Level.SEVERE, null, ex);
        } catch (ParseException ex) {
            //output is already done, just exit
            System.exit(1);
        } catch (Exception ex) {
            Logger.getGlobal().log(Level.SEVERE, null, ex);
        } finally {
            if (instance != null) {
                try {
                    instance.shutdown();
                } catch (MessagingException ex) {
                    Logger.getGlobal().log(Level.SEVERE, null, ex);
                }
            }
        }
    }
}
