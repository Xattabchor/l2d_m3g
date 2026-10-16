package rmn20.j2me;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;
import javax.microedition.midlet.MIDlet;

/** File browser and launcher for the on-device OBJ to 3D2 converter. */
public final class ConverterMIDlet extends MIDlet implements CommandListener {
    private final Command backCommand = new Command("Back", Command.BACK, 1);
    private final Command exitCommand = new Command("Exit", Command.EXIT, 2);
    private Display display;
    private List browser;
    private Vector itemPaths;
    private Vector itemKinds;
    private String currentDirectory;
    private String currentRoot;

    protected void startApp() {
        display = Display.getDisplay(this);
        showRoots();
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
    }

    public void commandAction(Command command, Displayable screen) {
        if(command == exitCommand) {
            notifyDestroyed();
            return;
        }
        if(command == backCommand) {
            if(currentDirectory != null && !currentDirectory.equals(currentRoot)) {
                openDirectory(parentOf(currentDirectory));
            } else {
                showRoots();
            }
            return;
        }
        if(screen == browser && command == List.SELECT_COMMAND) {
            int selected = browser.getSelectedIndex();
            if(selected < 0 || selected >= itemPaths.size()) return;
            String path = (String) itemPaths.elementAt(selected);
            int kind = ((Integer) itemKinds.elementAt(selected)).intValue();
            if(kind == 2) {
                openDirectory(parentOf(currentDirectory));
            } else if(kind == 1) {
                currentRoot = path;
                openDirectory(path);
            } else if(kind == 0) {
                if(isObj(path)) convert(path);
                else openDirectory(path);
            }
        }
    }

    private void showRoots() {
        try {
            List list = new List("Choose storage", List.IMPLICIT);
            itemPaths = new Vector();
            itemKinds = new Vector();
            currentDirectory = null;
            currentRoot = null;
            Enumeration roots = FileSystemRegistry.listRoots();
            while(roots != null && roots.hasMoreElements()) {
                String root = (String) roots.nextElement();
                String uri = "file:///" + encodePath(root);
                list.append(root, null);
                itemPaths.addElement(uri);
                itemKinds.addElement(new Integer(1));
            }
            if(list.size() == 0) {
                showMessage("No storage", "This device did not expose any file-system roots.");
                return;
            }
            showList(list);
        } catch(Throwable error) {
            showMessage("File access unavailable", message(error));
        }
    }

    private void openDirectory(String directory) {
        FileConnection connection = null;
        try {
            List list = new List("Choose an OBJ file", List.IMPLICIT);
            itemPaths = new Vector();
            itemKinds = new Vector();
            currentDirectory = directory;
            if(currentRoot != null && !directory.equals(currentRoot)) {
                list.append("../", null);
                itemPaths.addElement(parentOf(directory));
                itemKinds.addElement(new Integer(2));
            }

            connection = (FileConnection) Connector.open(directory, Connector.READ);
            Enumeration children = connection.list();
            while(children != null && children.hasMoreElements()) {
                String entry = (String) children.nextElement();
                boolean isDirectory = entry.endsWith("/");
                if(!isDirectory && !isObjName(entry)) continue;
                String childUri = directory + encodePath(entry);
                list.append(entry, null);
                itemPaths.addElement(childUri);
                itemKinds.addElement(new Integer(0));
            }
            if(itemPaths.size() == 0) list.append("No OBJ files", null);
            showList(list);
        } catch(Throwable error) {
            showMessage("Cannot open folder", message(error));
        } finally {
            if(connection != null) {
                try { connection.close(); } catch(Exception ignored) { }
            }
        }
    }

    private void showList(List list) {
        list.addCommand(backCommand);
        list.addCommand(exitCommand);
        list.setCommandListener(this);
        browser = list;
        display.setCurrent(list);
    }

    private void convert(String inputPath) {
        FileConnection inputFile = null;
        FileConnection outputFile = null;
        InputStream input = null;
        OutputStream output = null;
        String outputPath = outputPathFor(inputPath);
        try {
            inputFile = (FileConnection) Connector.open(inputPath, Connector.READ);
            outputFile = (FileConnection) Connector.open(outputPath, Connector.READ_WRITE);
            if(outputFile.exists()) outputFile.delete();
            outputFile.create();
            input = inputFile.openInputStream();
            output = outputFile.openOutputStream();
            Obj3D2Converter.Result result = Obj3D2Converter.convert(input, output);
            output.flush();
            showMessage("Conversion complete",
                    result.vertices + " vertices, " + result.triangles + " triangles\n" + fileName(outputPath));
        } catch(Throwable error) {
            showMessage("Conversion failed", message(error));
        } finally {
            if(input != null) try { input.close(); } catch(Exception ignored) { }
            if(output != null) try { output.close(); } catch(Exception ignored) { }
            if(inputFile != null) try { inputFile.close(); } catch(Exception ignored) { }
            if(outputFile != null) try { outputFile.close(); } catch(Exception ignored) { }
        }
    }

    private void showMessage(String title, String text) {
        Alert alert = new Alert(title, text, null, AlertType.INFO);
        alert.setTimeout(Alert.FOREVER);
        if(display != null) {
            if(browser == null) display.setCurrent(alert);
            else display.setCurrent(alert, browser);
        }
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null ? error.toString() : text;
    }

    private static boolean isObj(String path) {
        return isObjName(path);
    }

    private static boolean isObjName(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".obj");
    }

    private static String outputPathFor(String input) {
        int slash = input.lastIndexOf('/');
        int dot = input.lastIndexOf('.');
        if(dot < slash) dot = input.length();
        return input.substring(0, dot) + ".3d2";
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1);
    }

    private static String parentOf(String directory) {
        int end = directory.length();
        if(end > 0 && directory.charAt(end - 1) == '/') end--;
        int slash = directory.lastIndexOf('/', end - 1);
        if(slash < 0) return "file:///";
        return directory.substring(0, slash + 1);
    }

    /** Percent-encodes path characters while leaving URI separators intact. */
    private static String encodePath(String value) {
        StringBuffer encoded = new StringBuffer();
        for(int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if(isSafePathChar(ch)) {
                encoded.append(ch);
            } else {
                String part = String.valueOf(ch);
                try {
                    byte[] bytes = part.getBytes("UTF-8");
                    for(int b = 0; b < bytes.length; b++) {
                        int valueByte = bytes[b] & 255;
                        encoded.append('%');
                        encoded.append("0123456789ABCDEF".charAt(valueByte >> 4));
                        encoded.append("0123456789ABCDEF".charAt(valueByte & 15));
                    }
                } catch(Exception unsupportedEncoding) {
                    encoded.append('_');
                }
            }
        }
        return encoded.toString();
    }

    private static boolean isSafePathChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '/' || ch == ':'
                || ch == '.' || ch == '-' || ch == '_' || ch == '~';
    }
}
