import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebServer {
    private static final String MYSQL_HOST_URL = "jdbc:mysql://localhost:3306/";
    private static final String DB_NAME = "BB_keeper";
    private static final String USER = "root";
    private static final String PASSWORD = "Nj20062005$";
    private static final int PORT = 5000;

    public static void main(String[] args) {
        System.out.println("Initializing Database and Web Server...");

        // Load JDBC Driver class
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("Warning: MySQL JDBC Driver not found in classpath!");
        }

        // 1. Initialize Database Schema and Tables
        try {
            initDatabase();
            System.out.println("Database and tables initialized successfully.");
        } catch (Exception e) {
            System.err.println("CRITICAL: Database initialization failed!");
            e.printStackTrace();
        }

        // 2. Start Lightweight HTTP Server
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            
            // Serve static frontend files
            server.createContext("/", new StaticFileHandler());
            
            // REST API Endpoints
            server.createContext("/api/dashboard/stats", new DashboardStatsHandler());
            server.createContext("/api/donors", new DonorsHandler());
            server.createContext("/api/recipients", new RecipientsHandler());
            server.createContext("/api/stock", new StockHandler());
            server.createContext("/api/requests", new RequestsHandler());
            server.createContext("/api/requests/fulfill", new FulfillRequestHandler());
            server.createContext("/api/bloodgroups", new BloodGroupsHandler());

            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            System.out.println("Premium Blood Bank Web Server started on http://localhost:" + PORT);
        } catch (IOException e) {
            System.err.println("CRITICAL: Failed to start Web Server!");
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            // fallback for older drivers just in case
        }
        return DriverManager.getConnection(MYSQL_HOST_URL + DB_NAME, USER, PASSWORD);
    }

    private static void initDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MYSQL_HOST_URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            
            // Create database if not exists
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DB_NAME);
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // 1. Create BloodGroup Table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS BloodGroup (" +
                "  BG_ID INT PRIMARY KEY," +
                "  BloodGroup VARCHAR(10) UNIQUE NOT NULL" +
                ")"
            );

            // Populate BloodGroup table with defaults if empty
            ResultSet rsBG = stmt.executeQuery("SELECT COUNT(*) FROM BloodGroup");
            if (rsBG.next() && rsBG.getInt(1) == 0) {
                System.out.println("Populating standard Blood Groups...");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (1, 'A+')");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (2, 'A-')");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (3, 'B+')");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (4, 'B-')");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (5, 'AB+')");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (6, 'AB-')");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (7, 'O+')");
                stmt.executeUpdate("INSERT INTO BloodGroup (BG_ID, BloodGroup) VALUES (8, 'O-')");
            }

            // 2. Create Donor Table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS Donor (" +
                "  DonorID INT PRIMARY KEY," +
                "  BG_ID INT NOT NULL," +
                "  Name VARCHAR(100) NOT NULL," +
                "  Gender VARCHAR(10)," +
                "  DateOfBirth DATE," +
                "  Phone VARCHAR(20)," +
                "  Address VARCHAR(255)," +
                "  LastDonationDate DATE," +
                "  EligibilityStatus VARCHAR(20)," +
                "  FOREIGN KEY (BG_ID) REFERENCES BloodGroup(BG_ID)" +
                ")"
            );

            // 3. Create BloodStock Table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS BloodStock (" +
                "  StockID INT PRIMARY KEY," +
                "  BG_ID INT NOT NULL," +
                "  QuantityAvailable DOUBLE NOT NULL," +
                "  ExpiryDate DATE," +
                "  FOREIGN KEY (BG_ID) REFERENCES BloodGroup(BG_ID)" +
                ")"
            );

            // 4. Create Recipient Table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS Recipient (" +
                "  RecipientID INT PRIMARY KEY," +
                "  BG_ID INT NOT NULL," +
                "  Name VARCHAR(100) NOT NULL," +
                "  Phone VARCHAR(20)," +
                "  RequestDate DATE," +
                "  FOREIGN KEY (BG_ID) REFERENCES BloodGroup(BG_ID)" +
                ")"
            );

            // 5. Create BloodRequest Table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS BloodRequest (" +
                "  RequestID INT PRIMARY KEY," +
                "  RecipientID INT NOT NULL," +
                "  BG_ID INT NOT NULL," +
                "  QuantityRequested DOUBLE NOT NULL," +
                "  Status VARCHAR(50) DEFAULT 'Pending'," +
                "  FulfillmentDate DATE," +
                "  FOREIGN KEY (RecipientID) REFERENCES Recipient(RecipientID)," +
                "  FOREIGN KEY (BG_ID) REFERENCES BloodGroup(BG_ID)" +
                ")"
            );
        }
    }

    // Static File Serving Handler
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }

            File file = new File("public" + path);
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "404 Not Found");
                return;
            }

            String contentType = "text/plain";
            if (path.endsWith(".html")) contentType = "text/html";
            else if (path.endsWith(".css")) contentType = "text/css";
            else if (path.endsWith(".js")) contentType = "application/javascript";
            else if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) contentType = "image/jpeg";
            else if (path.endsWith(".ico")) contentType = "image/x-icon";

            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, file.length());

            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(file.toPath(), os);
            }
        }
    }

    // Helper method to parse JSON input
    private static String getBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private static String getJsonStringField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static Integer getJsonIntField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([0-9]+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static Double getJsonDoubleField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([0-9.]+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return 0.0;
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Enable CORS
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        String json = "{\"error\": \"" + msg + "\"}";
        sendJsonResponse(exchange, code, json);
    }

    // CORS preflight handler
    private static boolean handleOptions(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    // 1. Dashboard Stats Handler
    static class DashboardStatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {

                int totalDonors = 0;
                int totalRecipients = 0;
                int totalRequests = 0;
                double totalStock = 0;
                int lowStockAlerts = 0;

                ResultSet rs1 = stmt.executeQuery("SELECT COUNT(*) FROM Donor");
                if (rs1.next()) totalDonors = rs1.getInt(1);

                ResultSet rs2 = stmt.executeQuery("SELECT COUNT(*) FROM Recipient");
                if (rs2.next()) totalRecipients = rs2.getInt(1);

                ResultSet rs3 = stmt.executeQuery("SELECT COUNT(*) FROM BloodRequest");
                if (rs3.next()) totalRequests = rs3.getInt(1);

                ResultSet rs4 = stmt.executeQuery("SELECT SUM(QuantityAvailable) FROM BloodStock");
                if (rs4.next()) totalStock = rs4.getDouble(1);

                // Count blood groups where total stock is < 10 units (liters or bags)
                ResultSet rs5 = stmt.executeQuery(
                    "SELECT COUNT(*) FROM (SELECT BG_ID, SUM(QuantityAvailable) as qty FROM BloodStock GROUP BY BG_ID HAVING qty < 10.0) as low"
                );
                if (rs5.next()) lowStockAlerts = rs5.getInt(1);

                // Group-wise stock chart data
                List<String> stockData = new ArrayList<>();
                ResultSet rs6 = stmt.executeQuery(
                    "SELECT bg.BloodGroup, COALESCE(SUM(bs.QuantityAvailable), 0) as qty " +
                    "FROM BloodGroup bg LEFT JOIN BloodStock bs ON bg.BG_ID = bs.BG_ID " +
                    "GROUP BY bg.BloodGroup"
                );
                while (rs6.next()) {
                    stockData.add("\"" + rs6.getString("BloodGroup") + "\": " + rs6.getDouble("qty"));
                }

                String json = "{" +
                        "\"totalDonors\": " + totalDonors + "," +
                        "\"totalRecipients\": " + totalRecipients + "," +
                        "\"totalRequests\": " + totalRequests + "," +
                        "\"totalStock\": " + totalStock + "," +
                        "\"lowStockAlerts\": " + lowStockAlerts + "," +
                        "\"stockByGroup\": {" + String.join(",", stockData) + "}" +
                        "}";

                sendJsonResponse(exchange, 200, json);
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database Error: " + e.getMessage());
            }
        }
    }

    // 2. Donors Handler
    static class DonorsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT d.*, bg.BloodGroup FROM Donor d JOIN BloodGroup bg ON d.BG_ID = bg.BG_ID ORDER BY d.DonorID DESC")) {
                    
                    List<String> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add("{" +
                                "\"DonorID\": " + rs.getInt("DonorID") + "," +
                                "\"BG_ID\": " + rs.getInt("BG_ID") + "," +
                                "\"BloodGroup\": \"" + rs.getString("BloodGroup") + "\"," +
                                "\"Name\": \"" + rs.getString("Name") + "\"," +
                                "\"Gender\": \"" + rs.getString("Gender") + "\"," +
                                "\"DateOfBirth\": \"" + rs.getDate("DateOfBirth") + "\"," +
                                "\"Phone\": \"" + rs.getString("Phone") + "\"," +
                                "\"Address\": \"" + rs.getString("Address") + "\"," +
                                "\"LastDonationDate\": \"" + rs.getDate("LastDonationDate") + "\"," +
                                "\"EligibilityStatus\": \"" + rs.getString("EligibilityStatus") + "\"" +
                                "}");
                    }
                    sendJsonResponse(exchange, 200, "[" + String.join(",", list) + "]");
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                try {
                    String body = getBody(exchange);
                    int id = getJsonIntField(body, "DonorID");
                    int bgid = getJsonIntField(body, "BG_ID");
                    String name = getJsonStringField(body, "Name");
                    String gender = getJsonStringField(body, "Gender");
                    String dob = getJsonStringField(body, "DateOfBirth");
                    String phone = getJsonStringField(body, "Phone");
                    String addr = getJsonStringField(body, "Address");
                    String ldd = getJsonStringField(body, "LastDonationDate");
                    String status = getJsonStringField(body, "EligibilityStatus");

                    try (Connection conn = getConnection();
                         PreparedStatement ps = conn.prepareStatement(
                                 "INSERT INTO Donor (DonorID, BG_ID, Name, Gender, DateOfBirth, Phone, Address, LastDonationDate, EligibilityStatus) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        ps.setInt(1, id);
                        ps.setInt(2, bgid);
                        ps.setString(3, name);
                        ps.setString(4, gender);
                        ps.setDate(5, Date.valueOf(dob));
                        ps.setString(6, phone);
                        ps.setString(7, addr);
                        ps.setDate(8, Date.valueOf(ldd));
                        ps.setString(9, status);
                        ps.executeUpdate();
                    }
                    sendJsonResponse(exchange, 200, "{\"success\": true}");
                } catch (Exception e) {
                    sendError(exchange, 400, "Invalid input: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    // 3. Recipients Handler
    static class RecipientsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT r.*, bg.BloodGroup FROM Recipient r JOIN BloodGroup bg ON r.BG_ID = bg.BG_ID ORDER BY r.RecipientID DESC")) {
                    
                    List<String> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add("{" +
                                "\"RecipientID\": " + rs.getInt("RecipientID") + "," +
                                "\"BG_ID\": " + rs.getInt("BG_ID") + "," +
                                "\"BloodGroup\": \"" + rs.getString("BloodGroup") + "\"," +
                                "\"Name\": \"" + rs.getString("Name") + "\"," +
                                "\"Phone\": \"" + rs.getString("Phone") + "\"," +
                                "\"RequestDate\": \"" + rs.getDate("RequestDate") + "\"" +
                                "}");
                    }
                    sendJsonResponse(exchange, 200, "[" + String.join(",", list) + "]");
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                try {
                    String body = getBody(exchange);
                    int id = getJsonIntField(body, "RecipientID");
                    int bgid = getJsonIntField(body, "BG_ID");
                    String name = getJsonStringField(body, "Name");
                    String phone = getJsonStringField(body, "Phone");
                    String rdate = getJsonStringField(body, "RequestDate");

                    try (Connection conn = getConnection();
                         PreparedStatement ps = conn.prepareStatement(
                                 "INSERT INTO Recipient (RecipientID, BG_ID, Name, Phone, RequestDate) VALUES (?, ?, ?, ?, ?)")) {
                        ps.setInt(1, id);
                        ps.setInt(2, bgid);
                        ps.setString(3, name);
                        ps.setString(4, phone);
                        ps.setDate(5, Date.valueOf(rdate));
                        ps.executeUpdate();
                    }
                    sendJsonResponse(exchange, 200, "{\"success\": true}");
                } catch (Exception e) {
                    sendError(exchange, 400, "Invalid input: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    // 4. Stock Handler
    static class StockHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT bs.*, bg.BloodGroup FROM BloodStock bs JOIN BloodGroup bg ON bs.BG_ID = bg.BG_ID ORDER BY bs.StockID DESC")) {
                    
                    List<String> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add("{" +
                                "\"StockID\": " + rs.getInt("StockID") + "," +
                                "\"BG_ID\": " + rs.getInt("BG_ID") + "," +
                                "\"BloodGroup\": \"" + rs.getString("BloodGroup") + "\"," +
                                "\"QuantityAvailable\": " + rs.getDouble("QuantityAvailable") + "," +
                                "\"ExpiryDate\": \"" + rs.getDate("ExpiryDate") + "\"" +
                                "}");
                    }
                    sendJsonResponse(exchange, 200, "[" + String.join(",", list) + "]");
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                try {
                    String body = getBody(exchange);
                    int id = getJsonIntField(body, "StockID");
                    int bgid = getJsonIntField(body, "BG_ID");
                    double qty = getJsonDoubleField(body, "QuantityAvailable");
                    String edate = getJsonStringField(body, "ExpiryDate");

                    try (Connection conn = getConnection();
                         PreparedStatement ps = conn.prepareStatement(
                                 "INSERT INTO BloodStock (StockID, BG_ID, QuantityAvailable, ExpiryDate) VALUES (?, ?, ?, ?)")) {
                        ps.setInt(1, id);
                        ps.setInt(2, bgid);
                        ps.setDouble(3, qty);
                        ps.setDate(4, Date.valueOf(edate));
                        ps.executeUpdate();
                    }
                    sendJsonResponse(exchange, 200, "{\"success\": true}");
                } catch (Exception e) {
                    sendError(exchange, 400, "Invalid input: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    // 5. Requests Handler
    static class RequestsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT br.*, r.Name as RecipientName, bg.BloodGroup " +
                             "FROM BloodRequest br " +
                             "JOIN Recipient r ON br.RecipientID = r.RecipientID " +
                             "JOIN BloodGroup bg ON br.BG_ID = bg.BG_ID " +
                             "ORDER BY br.RequestID DESC")) {
                    
                    List<String> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add("{" +
                                "\"RequestID\": " + rs.getInt("RequestID") + "," +
                                "\"RecipientID\": " + rs.getInt("RecipientID") + "," +
                                "\"RecipientName\": \"" + rs.getString("RecipientName") + "\"," +
                                "\"BG_ID\": " + rs.getInt("BG_ID") + "," +
                                "\"BloodGroup\": \"" + rs.getString("BloodGroup") + "\"," +
                                "\"QuantityRequested\": " + rs.getDouble("QuantityRequested") + "," +
                                "\"Status\": \"" + rs.getString("Status") + "\"," +
                                "\"FulfillmentDate\": \"" + (rs.getDate("FulfillmentDate") != null ? rs.getDate("FulfillmentDate").toString() : "") + "\"" +
                                "}");
                    }
                    sendJsonResponse(exchange, 200, "[" + String.join(",", list) + "]");
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                try {
                    String body = getBody(exchange);
                    int id = getJsonIntField(body, "RequestID");
                    int recID = getJsonIntField(body, "RecipientID");
                    int bgid = getJsonIntField(body, "BG_ID");
                    double qty = getJsonDoubleField(body, "QuantityRequested");
                    String status = getJsonStringField(body, "Status");
                    String fdate = getJsonStringField(body, "FulfillmentDate");

                    try (Connection conn = getConnection();
                         PreparedStatement ps = conn.prepareStatement(
                                 "INSERT INTO BloodRequest (RequestID, RecipientID, BG_ID, QuantityRequested, Status, FulfillmentDate) VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setInt(1, id);
                        ps.setInt(2, recID);
                        ps.setInt(3, bgid);
                        ps.setDouble(4, qty);
                        ps.setString(5, status.isEmpty() ? "Pending" : status);
                        if (fdate == null || fdate.trim().isEmpty()) {
                            ps.setNull(6, Types.DATE);
                        } else {
                            ps.setDate(6, Date.valueOf(fdate));
                        }
                        ps.executeUpdate();
                    }
                    sendJsonResponse(exchange, 200, "{\"success\": true}");
                } catch (Exception e) {
                    sendError(exchange, 400, "Invalid input: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    // 6. Fulfill Request Handler (Special Transactional Action)
    static class FulfillRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            try {
                String body = getBody(exchange);
                int requestID = getJsonIntField(body, "RequestID");
                String action = getJsonStringField(body, "Action"); // "Approve", "Reject", "Consume"

                try (Connection conn = getConnection()) {
                    conn.setAutoCommit(false); // Make transaction

                    try {
                        // 1. Fetch the request details
                        int bgid = 0;
                        double qtyRequested = 0;
                        String currentStatus = "";
                        try (PreparedStatement psReq = conn.prepareStatement("SELECT BG_ID, QuantityRequested, Status FROM BloodRequest WHERE RequestID = ?")) {
                            psReq.setInt(1, requestID);
                            try (ResultSet rsReq = psReq.executeQuery()) {
                                if (rsReq.next()) {
                                    bgid = rsReq.getInt("BG_ID");
                                    qtyRequested = rsReq.getDouble("QuantityRequested");
                                    currentStatus = rsReq.getString("Status");
                                } else {
                                    conn.rollback();
                                    sendError(exchange, 404, "Blood Request not found.");
                                    return;
                                }
                            }
                        }

                        if ("Approved".equalsIgnoreCase(currentStatus) && "Approve".equalsIgnoreCase(action)) {
                            conn.rollback();
                            sendError(exchange, 400, "Request is already Approved.");
                            return;
                        }

                        if ("Consumed".equalsIgnoreCase(currentStatus)) {
                            conn.rollback();
                            sendError(exchange, 400, "Request is already fulfilled/consumed.");
                            return;
                        }

                        if ("Approved".equalsIgnoreCase(action)) {
                            // Verify sufficient stock exists for this Blood Group
                            double availableStock = 0;
                            try (PreparedStatement psStock = conn.prepareStatement("SELECT SUM(QuantityAvailable) FROM BloodStock WHERE BG_ID = ?")) {
                                psStock.setInt(1, bgid);
                                try (ResultSet rsStock = psStock.executeQuery()) {
                                    if (rsStock.next()) {
                                        availableStock = rsStock.getDouble(1);
                                    }
                                }
                            }

                            if (availableStock < qtyRequested) {
                                conn.rollback();
                                sendError(exchange, 400, "Insufficient stock! Available: " + availableStock + " units, Requested: " + qtyRequested + " units.");
                                return;
                            }

                            // Update request status
                            try (PreparedStatement psUpReq = conn.prepareStatement("UPDATE BloodRequest SET Status = 'Approved' WHERE RequestID = ?")) {
                                psUpReq.setInt(1, requestID);
                                psUpReq.executeUpdate();
                            }
                        } else if ("Reject".equalsIgnoreCase(action)) {
                            // Update request status to Rejected
                            try (PreparedStatement psUpReq = conn.prepareStatement("UPDATE BloodRequest SET Status = 'Rejected' WHERE RequestID = ?")) {
                                psUpReq.setInt(1, requestID);
                                psUpReq.executeUpdate();
                            }
                        } else if ("Consume".equalsIgnoreCase(action)) {
                            // Double check stock and deduct quantity
                            double availableStock = 0;
                            try (PreparedStatement psStock = conn.prepareStatement("SELECT SUM(QuantityAvailable) FROM BloodStock WHERE BG_ID = ?")) {
                                psStock.setInt(1, bgid);
                                try (ResultSet rsStock = psStock.executeQuery()) {
                                    if (rsStock.next()) {
                                        availableStock = rsStock.getDouble(1);
                                    }
                                }
                            }

                            if (availableStock < qtyRequested) {
                                conn.rollback();
                                sendError(exchange, 400, "Insufficient stock to consume! Available: " + availableStock + " units.");
                                return;
                            }

                            // Deduct stock from the oldest stock items (FIFO)
                            double remainingToDeduct = qtyRequested;
                            try (PreparedStatement psFetchStock = conn.prepareStatement("SELECT StockID, QuantityAvailable FROM BloodStock WHERE BG_ID = ? ORDER BY ExpiryDate ASC, StockID ASC")) {
                                psFetchStock.setInt(1, bgid);
                                try (ResultSet rsStockList = psFetchStock.executeQuery()) {
                                    while (rsStockList.next() && remainingToDeduct > 0) {
                                        int stockID = rsStockList.getInt("StockID");
                                        double stockQty = rsStockList.getDouble("QuantityAvailable");

                                        if (stockQty <= remainingToDeduct) {
                                            // Delete the empty stock item
                                            try (PreparedStatement psDelStock = conn.prepareStatement("DELETE FROM BloodStock WHERE StockID = ?")) {
                                                psDelStock.setInt(1, stockID);
                                                psDelStock.executeUpdate();
                                            }
                                            remainingToDeduct -= stockQty;
                                        } else {
                                            // Deduct partial quantity
                                            try (PreparedStatement psUpStock = conn.prepareStatement("UPDATE BloodStock SET QuantityAvailable = ? WHERE StockID = ?")) {
                                                psUpStock.setDouble(1, stockQty - remainingToDeduct);
                                                psUpStock.setInt(2, stockID);
                                                psUpStock.executeUpdate();
                                            }
                                            remainingToDeduct = 0;
                                        }
                                    }
                                }
                            }

                            // Update request status to consumed & set FulfillmentDate
                            java.sql.Date today = new java.sql.Date(System.currentTimeMillis());
                            try (PreparedStatement psUpReq = conn.prepareStatement("UPDATE BloodRequest SET Status = 'Consumed', FulfillmentDate = ? WHERE RequestID = ?")) {
                                psUpReq.setDate(1, today);
                                psUpReq.setInt(2, requestID);
                                psUpReq.executeUpdate();
                            }
                        }

                        conn.commit();
                        sendJsonResponse(exchange, 200, "{\"success\": true}");
                    } catch (Exception innerEx) {
                        conn.rollback();
                        throw innerEx;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database Error: " + e.getMessage());
            }
        }
    }

    // 7. Blood Groups Fetch Handler
    static class BloodGroupsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM BloodGroup ORDER BY BG_ID ASC")) {
                
                List<String> list = new ArrayList<>();
                while (rs.next()) {
                    list.add("{" +
                            "\"BG_ID\": " + rs.getInt("BG_ID") + "," +
                            "\"BloodGroup\": \"" + rs.getString("BloodGroup") + "\"" +
                            "}");
                }
                sendJsonResponse(exchange, 200, "[" + String.join(",", list) + "]");
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }
}
