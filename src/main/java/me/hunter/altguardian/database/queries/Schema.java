package me.hunter.altguardian.database.queries;

/**
 * Contains SQL statements for creating the database schema (tables and indexes).
 * Using constants makes the DatabaseManager cleaner.
 */
public final class Schema {

    // Private constructor to prevent instantiation of this utility class
    private Schema() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // --- Table Creation ---

    // Defines the main table storing player account information.
    public static final String CREATE_ACCOUNTS_TABLE =
            "CREATE TABLE IF NOT EXISTS Accounts (" +
                    " AccountID INTEGER PRIMARY KEY AUTOINCREMENT," + // Unique internal ID for each account record
                    " Username TEXT NOT NULL UNIQUE," +             // Player's username (case preserved as stored, UNIQUE constraint)
                    " RegistrationDate INTEGER NOT NULL," +         // Unix timestamp (milliseconds) of first seen/registration
                    " LastLoginDate INTEGER NOT NULL," +            // Unix timestamp (milliseconds) of last successful login
                    " OfflineUUID TEXT" +                           // Offline mode UUID captured at registration (nullable, stored as text)
                    ");";

    // Defines the table storing the history of IP addresses used by accounts.
    public static final String CREATE_IP_HISTORY_TABLE =
            "CREATE TABLE IF NOT EXISTS IP_History (" +
                    " AccountID INTEGER NOT NULL," +                // Foreign key linking to the Accounts table
                    " IPAddress TEXT NOT NULL," +                   // IP address used (stored as text)
                    " FirstUsed INTEGER NOT NULL," +                // Unix timestamp (milliseconds) when this IP was first used by this account
                    " LastUsed INTEGER NOT NULL," +                 // Unix timestamp (milliseconds) when this IP was last used by this account
                    " PRIMARY KEY (AccountID, IPAddress)," +        // Composite primary key: an account can only have one entry per unique IP
                    " FOREIGN KEY (AccountID) REFERENCES Accounts(AccountID) ON DELETE CASCADE" + // If an account is deleted, its IP history is also deleted
                    ");";

    // Defines the table storing flags associated with player accounts.
    public static final String CREATE_FLAGS_TABLE =
            "CREATE TABLE IF NOT EXISTS Flags (" +
                    " AccountID INTEGER NOT NULL," +              // Foreign key linking to the Accounts table
                    " FlagReason TEXT NOT NULL," +                // The reason/identifier for the flag (e.g., "Potential Alt", "Impersonation", stored as text)
                    " FlaggedAt INTEGER NOT NULL," +              // Unix timestamp (milliseconds) when the flag was added
                    " PRIMARY KEY (AccountID, FlagReason)," +     // Composite primary key: an account cannot have the same flag reason applied multiple times
                    " FOREIGN KEY (AccountID) REFERENCES Accounts(AccountID) ON DELETE CASCADE" + // If an account is deleted, its flags are also deleted
                    ");";


    // --- Index Creation (CRUCIAL FOR PERFORMANCE!) ---

    // Index for quickly finding accounts by username (case-insensitive search handled via LOWER() in query)
    public static final String CREATE_IDX_ACCOUNTS_USERNAME =
            "CREATE INDEX IF NOT EXISTS idx_accounts_username_lower ON Accounts(LOWER(Username));"; // Index on lowercase username

    // Index for quickly finding IP history records for a specific account
    public static final String CREATE_IDX_IP_HISTORY_ACCOUNT_ID =
            "CREATE INDEX IF NOT EXISTS idx_ip_history_account_id ON IP_History(AccountID);";

    // Index for quickly finding accounts associated with a specific IP address
    public static final String CREATE_IDX_IP_HISTORY_IP_ADDRESS =
            "CREATE INDEX IF NOT EXISTS idx_ip_history_ip_address ON IP_History(IPAddress);";

    // Index for purging old IP history records efficiently based on the LastUsed timestamp
    public static final String CREATE_IDX_IP_HISTORY_LAST_USED =
            "CREATE INDEX IF NOT EXISTS idx_ip_history_last_used ON IP_History(LastUsed);";

    // Index for quickly finding flags associated with a specific account
    public static final String CREATE_IDX_FLAGS_ACCOUNT_ID =
            "CREATE INDEX IF NOT EXISTS idx_flags_account_id ON Flags(AccountID);";

}