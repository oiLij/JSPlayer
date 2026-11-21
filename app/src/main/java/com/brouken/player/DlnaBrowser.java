package com.brouken.player;

import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DlnaBrowser {

    public static class DlnaItem {
        public final String id;
        public final String title;
        public final String url; // controlURL for server, stream URL for media, null for folder
        public final boolean isServer;
        public final boolean isFolder;

        public DlnaItem(String id, String title, String url, boolean isServer, boolean isFolder) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.isServer = isServer;
            this.isFolder = isFolder;
        }
    }

    public interface Callback {
        void onResult(List<DlnaItem> items);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void discoverServers(Callback callback) {
        executor.execute(() -> {
            List<DlnaItem> servers = new ArrayList<>();
            Set<String> locations = new HashSet<>();
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1800);
                String msg = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: 239.255.255.250:1900\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: urn:schemas-upnp-org:service:ContentDirectory:1\r\n\r\n";
                byte[] sendData = msg.getBytes(StandardCharsets.UTF_8);
                DatagramPacket sendPacket = new DatagramPacket(
                        sendData, sendData.length, InetAddress.getByName("239.255.255.250"), 1900);
                socket.send(sendPacket);

                byte[] buf = new byte[2048];
                long end = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket recv = new DatagramPacket(buf, buf.length);
                        socket.receive(recv);
                        String resp = new String(recv.getData(), 0, recv.getLength(), StandardCharsets.UTF_8);
                        for (String line : resp.split("\r\n")) {
                            if (line.toLowerCase().startsWith("location:")) {
                                locations.add(line.substring(9).trim());
                            }
                        }
                    } catch (Exception ignored) {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            for (String loc : locations) {
                DlnaItem server = parseDeviceDescription(loc);
                if (server != null) {
                    servers.add(server);
                }
            }
            mainHandler.post(() -> callback.onResult(servers));
        });
    }

    private static DlnaItem parseDeviceDescription(String locationUrl) {
        try {
            URL base = new URL(locationUrl);
            HttpURLConnection conn = (HttpURLConnection) base.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            try (InputStream is = conn.getInputStream()) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(is, "UTF-8");
                String friendlyName = null;
                String controlUrl = null;
                boolean inContentDir = false;
                String currentTag = null;

                int event = p.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        currentTag = p.getName();
                    } else if (event == XmlPullParser.TEXT) {
                        String text = p.getText() != null ? p.getText().trim() : "";
                        if ("friendlyName".equals(currentTag) && friendlyName == null && !text.isEmpty()) {
                            friendlyName = text;
                        } else if ("serviceType".equals(currentTag) && text.contains("ContentDirectory:1")) {
                            inContentDir = true;
                        } else if ("controlURL".equals(currentTag) && inContentDir && controlUrl == null && !text.isEmpty()) {
                            controlUrl = new URL(base, text).toString();
                        }
                    } else if (event == XmlPullParser.END_TAG) {
                        if ("service".equals(p.getName())) {
                            inContentDir = false;
                        }
                        currentTag = null;
                    }
                    event = p.next();
                }
                if (controlUrl != null) {
                    return new DlnaItem("0", friendlyName != null ? friendlyName : base.getHost(), controlUrl, true, true);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void browse(String controlUrl, String objectId, Callback callback) {
        executor.execute(() -> {
            List<DlnaItem> items = new ArrayList<>();
            try {
                String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                        + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                        + "<s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
                        + "<ObjectID>" + objectId + "</ObjectID>"
                        + "<BrowseFlag>BrowseDirectChildren</BrowseFlag>"
                        + "<Filter>*</Filter><StartingIndex>0</StartingIndex>"
                        + "<RequestedCount>500</RequestedCount><SortCriteria></SortCriteria>"
                        + "</u:Browse></s:Body></s:Envelope>";

                HttpURLConnection conn = (HttpURLConnection) new URL(controlUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(soap.getBytes(StandardCharsets.UTF_8));
                }

                String didl = null;
                try (InputStream is = conn.getInputStream()) {
                    XmlPullParser p = Xml.newPullParser();
                    p.setInput(is, "UTF-8");
                    int event = p.getEventType();
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && "Result".equals(p.getName())) {
                            didl = p.nextText();
                            break;
                        }
                        event = p.next();
                    }
                }

                if (didl != null) {
                    parseDidl(didl, items);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            mainHandler.post(() -> callback.onResult(items));
        });
    }

    private static void parseDidl(String didl, List<DlnaItem> out) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(didl));
        int event = p.getEventType();
        String id = null, title = null, resUrl = null;
        boolean inContainer = false, inItem = false;

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("container".equals(name)) {
                    inContainer = true;
                    id = p.getAttributeValue(null, "id");
                    title = null;
                } else if ("item".equals(name)) {
                    inItem = true;
                    id = p.getAttributeValue(null, "id");
                    title = null;
                    resUrl = null;
                } else if ((inContainer || inItem) && "title".equals(name)) {
                    title = p.nextText();
                } else if (inItem && "res".equals(name) && resUrl == null) {
                    resUrl = p.nextText();
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = p.getName();
                if ("container".equals(name) && inContainer) {
                    if (id != null && title != null) {
                        out.add(new DlnaItem(id, "[Folder] " + title, null, false, true));
                    }
                    inContainer = false;
                } else if ("item".equals(name) && inItem) {
                    if (resUrl != null && title != null) {
                        out.add(new DlnaItem(id, title, resUrl, false, false));
                    }
                    inItem = false;
                }
            }
            event = p.next();
        }
    }
}
