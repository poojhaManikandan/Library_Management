import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;
public class LibraryManagement {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int choice = 0;
        while (choice != 5) {
            System.out.println("\n--- Library Management System ---");
            System.out.println("1. Add New Book");
            System.out.println("2. Issue Book");
            System.out.println("3. Return Book");
            System.out.println("4. Show All Available Books");
            System.out.println("5. Exit");
            System.out.print("Enter your choice: ");
            try {
                choice = Integer.parseInt(scanner.nextLine());
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number.");
                continue;
            }
            switch (choice) {
                case 1:
                    addNewBook(scanner);
                    break;
                case 2:
                    issueBook(scanner);
                    break;
                case 3:
                    returnBook(scanner);
                    break;
                case 4:
                    showAvailableBooks();
                    break;
                case 5:
                    System.out.println("Exiting... Thank you!");
                    break;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
        scanner.close();
    }
    public static void addNewBook(Scanner scanner) {
        System.out.print("Enter Book Title: ");
        String title = scanner.nextLine();
        System.out.print("Enter Book Author: ");
        String author = scanner.nextLine();
        System.out.print("Enter Quantity: ");
        int quantity = Integer.parseInt(scanner.nextLine());
        String sql = "INSERT INTO books (title, author, quantity) VALUES (?, ?, ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {a
            pstmt.setString(1, title);
            pstmt.setString(2, author);
            pstmt.setInt(3, quantity);
            int rowsAffected = pstmt.executeUpdate();
            if (rowsAffected > 0) {
                System.out.println("Book added successfully!");
            }
        } catch (SQLException e) {
            System.out.println("Error adding book: " + e.getMessage());
        }
    }

    public static void showAvailableBooks() {
        String sql = "SELECT id, title, author, quantity FROM books WHERE quantity > 0";

        try (Connection conn = DBUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            
            System.out.println("\n--- Available Books ---");
            System.out.printf("%-5s | %-30s | %-20s | %s%n", "ID", "Title", "Author", "Quantity");
            System.out.println("---------------------------------------------------------------------");

            boolean found = false;
            while (rs.next()) {
                found = true;
                System.out.printf("%-5d | %-30s | %-20s | %d%n",
                        rs.getInt("id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getInt("quantity"));
            }
            if (!found) {
                System.out.println("No books available in the library.");
            }
        } catch (SQLException e) {
            System.out.println("Error fetching books: " + e.getMessage());
        }
    }

    public static void issueBook(Scanner scanner) {
        showAvailableBooks();
        System.out.print("\nEnter the Book ID you want to issue: ");
        int bookId = Integer.parseInt(scanner.nextLine());
        System.out.print("Enter Member Name: ");
        String memberName = scanner.nextLine();

        // We use a transaction to ensure both operations (update quantity and insert into issued_books) succeed or fail together.
        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false); // Start transaction

            // 1. Check if book is available and get quantity
            String checkSql = "SELECT quantity FROM books WHERE id = ? AND quantity > 0";
            int quantity = 0;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setInt(1, bookId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        quantity = rs.getInt("quantity");
                    } else {
                        System.out.println("Book not found or is out of stock.");
                        conn.rollback(); // Rollback transaction
                        return;
                    }
                }
            }

            // 2. Decrease book quantity
            String updateSql = "UPDATE books SET quantity = ? WHERE id = ?";
            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setInt(1, quantity - 1);
                updateStmt.setInt(2, bookId);
                updateStmt.executeUpdate();
            }

            // 3. Add record to issued_books table
            String issueSql = "INSERT INTO issued_books (book_id, member_name, issue_date) VALUES (?, ?, CURDATE())";
            try (PreparedStatement issueStmt = conn.prepareStatement(issueSql)) {
                issueStmt.setInt(1, bookId);
                issueStmt.setString(2, memberName);
                issueStmt.executeUpdate();
            }

            conn.commit(); // Commit transaction
            System.out.println("Book issued successfully to " + memberName);

        } catch (SQLException e) {
            System.out.println("Error issuing book: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback(); // Rollback on error
                    System.out.println("Transaction rolled back.");
                } catch (SQLException ex) {
                    System.out.println("Error rolling back: " + ex.getMessage());
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void returnBook(Scanner scanner) {
        System.out.print("Enter Book ID to return: ");
        int bookId = Integer.parseInt(scanner.nextLine());
        System.out.print("Enter Member Name who is returning: ");
        String memberName = scanner.nextLine();

        // We will find the *latest* non-returned book for this member and ID
        String selectSql = "SELECT issue_id FROM issued_books WHERE book_id = ? AND member_name = ? AND return_date IS NULL ORDER BY issue_date DESC LIMIT 1";
        String updateIssueSql = "UPDATE issued_books SET return_date = CURDATE() WHERE issue_id = ?";
        String updateBookSql = "UPDATE books SET quantity = quantity + 1 WHERE id = ?";

        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false); // Start transaction

            int issueId = -1;
            // 1. Find the open issue record
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setInt(1, bookId);
                selectStmt.setString(2, memberName);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (rs.next()) {
                        issueId = rs.getInt("issue_id");
                    } else {
                        System.out.println("No matching issued book record found for this member.");
                        conn.rollback();
                        return;
                    }
                }
            }
            
            // 2. Mark the book as returned in issued_books
            try (PreparedStatement updateIssueStmt = conn.prepareStatement(updateIssueSql)) {
                updateIssueStmt.setInt(1, issueId);
                updateIssueStmt.executeUpdate();
            }

            // 3. Increment the book quantity in the books table
            try (PreparedStatement updateBookStmt = conn.prepareStatement(updateBookSql)) {
                updateBookStmt.setInt(1, bookId);
                updateBookStmt.executeUpdate();
            }
            
            conn.commit(); // Commit transaction
            System.out.println("Book returned successfully!");

        } catch (SQLException e) {
            System.out.println("Error returning book: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}