/*
 * Copyright 2014 Martin Winandy
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.pmw.tinylog.writers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pmw.tinylog.Configuration;
import org.pmw.tinylog.EnvironmentHelper;
import org.pmw.tinylog.InternalLogger;
import org.pmw.tinylog.LoggingLevel;

/**
 * Writes log entries to a SQL database.
 */
@PropertiesSupport(name = "jdbc", properties = { @Property(name = "driver", type = String.class, optional = true),
		@Property(name = "url", type = String.class), @Property(name = "batch", type = boolean.class, optional = true),
		@Property(name = "username", type = String.class, optional = true), @Property(name = "password", type = String.class, optional = true) })
public final class JdbcWriter implements LoggingWriter {

	private static final int MAX_BATCH_SIZE = 128;
	private static final String NEW_LINE = EnvironmentHelper.getNewLine();

	private final String url;
	private final String table;
	private final List<Value> values;
	private final Set<LogEntryValue> requiredLogEntryValues;
	private final boolean batch;
	private final String username;
	private final String password;

	private final Object lock = new Object();

	private String sql;
	private Connection connection;
	private PreparedStatement batchStatement;
	private int batchCount;

	/**
	 * @param url
	 *            JDBC connection URL
	 * @param table
	 *            Name of table
	 * @param values
	 *            Values to insert
	 */
	public JdbcWriter(final String url, final String table, final List<Value> values) {
		this(url, table, values, false, null, null);
	}

	/**
	 * @param url
	 *            JDBC connection URL
	 * @param table
	 *            Name of table
	 * @param values
	 *            Values to insert
	 * @param batch
	 *            <code>true</code> for collecting SQL statements and execute them in a batch process,
	 *            <code>false</code> to execute SQL statements immediately (default)
	 */
	public JdbcWriter(final String url, final String table, final List<Value> values, final boolean batch) {
		this(url, table, values, batch, null, null);
	}

	/**
	 * @param url
	 *            JDBC connection URL
	 * @param table
	 *            Name of table
	 * @param values
	 *            Values to insert
	 * @param username
	 *            User name for database log in
	 * @param password
	 *            Password for database log in
	 */
	public JdbcWriter(final String url, final String table, final List<Value> values, final String username, final String password) {
		this(url, table, values, false, username, password);
	}

	/**
	 * @param url
	 *            JDBC connection URL
	 * @param table
	 *            Name of table
	 * @param values
	 *            Values to insert
	 * @param batch
	 *            <code>true</code> for collecting SQL statements and execute them in a batch process,
	 *            <code>false</code> to execute SQL statements immediately (default)
	 * @param username
	 *            User name for database log in
	 * @param password
	 *            Password for database log in
	 */
	public JdbcWriter(final String url, final String table, final List<Value> values, final boolean batch, final String username, final String password) {
		this.url = url;
		this.table = table;
		this.values = values;
		this.requiredLogEntryValues = calculateRequiredLogEntryValues(values);
		this.batch = batch;
		this.username = username;
		this.password = password;
	}

	/**
	 * @param url
	 *            JDBC connection URL
	 * @param table
	 *            Name of table
	 * @param values
	 *            Values to insert
	 * @param username
	 *            User name for database log in
	 * @param password
	 *            Password for database log in
	 */
	JdbcWriter(final String url, final String table, final String[] values, final String username, final String password) {
		this(url, table, renderValues(values), false, username, password);
	}

	/**
	 * @param url
	 *            JDBC connection URL
	 * @param table
	 *            Name of table
	 * @param values
	 *            Values to insert
	 * @param batch
	 *            <code>true</code> for collecting SQL statements and execute them in a batch process,
	 *            <code>false</code> to execute SQL statements immediately (default)
	 * @param username
	 *            User name for database log in
	 * @param password
	 *            Password for database log in
	 */
	JdbcWriter(final String url, final String table, final String[] values, final boolean batch, final String username, final String password) {
		this(url, table, renderValues(values), batch, username, password);
	}

	/**
	 * Get the JDBC connection URL to database.
	 * 
	 * @return JDBC connection URL
	 */
	public String getUrl() {
		return url;
	}

	/**
	 * Get the name of the database table.
	 * 
	 * @return Name of the database table
	 */
	public String getTable() {
		return table;
	}

	/**
	 * Get all values to insert.
	 * 
	 * @return Values to insert
	 */
	public List<Value> getValues() {
		return Collections.unmodifiableList(values);
	}

	/**
	 * Get the user name for database log in.
	 * 
	 * @return User name for log in
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * Get the password for database log in.
	 * 
	 * @return Password for log in
	 */
	public String getPassword() {
		return password;
	}

	/**
	 * Determine whether batch mode is enabled.
	 * 
	 * @return <code>true</code> if batch mode is enabled, <code>false</code> if not
	 */
	public boolean isBatch() {
		return batch;
	}

	@Override
	public Set<LogEntryValue> getRequiredLogEntryValues() {
		return requiredLogEntryValues;
	}

	@Override
	public void init(final Configuration configuration) throws SQLException {
		if (username == null) {
			connection = DriverManager.getConnection(url);
		} else {
			connection = DriverManager.getConnection(url, username, password);
		}

		sql = renderSql(connection, table, values);

		if (batch) {
			batchStatement = connection.prepareStatement(sql);
			batchCount = 0;
		}
	}

	@Override
	public void write(final LogEntry logEntry) throws SQLException {
		if (batch) {
			synchronized (lock) {
				if (batchCount >= MAX_BATCH_SIZE) {
					executeBatch();
				}
				fillStatement(batchStatement, values, logEntry);
				batchStatement.addBatch();
				++batchCount;
			}
		} else {
			PreparedStatement statement = connection.prepareStatement(sql);
			try {
				fillStatement(statement, values, logEntry);
				statement.executeUpdate();
			} finally {
				statement.close();
			}
		}
	}

	@Override
	public void flush() throws SQLException {
		if (batch) {
			synchronized (lock) {
				if (batchCount > 0) {
					executeBatch();
				}
			}
		}
	}

	@Override
	public void close() throws SQLException {
		flush();
		connection.close();
	}

	private static List<Value> renderValues(final String[] strings) {
		List<Value> values = new ArrayList<>(strings.length);
		for (String string : strings) {
			if ("date".equalsIgnoreCase(string)) {
				values.add(Value.DATE);
			} else if ("pid".equalsIgnoreCase(string)) {
				values.add(Value.PROCESS_ID);
			} else if ("thread".equalsIgnoreCase(string)) {
				values.add(Value.THREAD_NAME);
			} else if ("thread_id".equalsIgnoreCase(string)) {
				values.add(Value.THREAD_ID);
			} else if ("class".equalsIgnoreCase(string)) {
				values.add(Value.CLASS);
			} else if ("class_name".equalsIgnoreCase(string)) {
				values.add(Value.CLASS_NAME);
			} else if ("package".equalsIgnoreCase(string)) {
				values.add(Value.PACKAGE);
			} else if ("method".equalsIgnoreCase(string)) {
				values.add(Value.METHOD);
			} else if ("file".equalsIgnoreCase(string)) {
				values.add(Value.FILE);
			} else if ("line".equalsIgnoreCase(string)) {
				values.add(Value.LINE_NUMBER);
			} else if ("level".equalsIgnoreCase(string)) {
				values.add(Value.LOGGING_LEVEL);
			} else if ("message".equalsIgnoreCase(string)) {
				values.add(Value.MESSAGE);
			} else if ("exception".equalsIgnoreCase(string)) {
				values.add(Value.EXCEPTION);
			} else if ("log_entry".equalsIgnoreCase(string)) {
				values.add(Value.RENDERED_LOG_ENTRY);
			} else {
				InternalLogger.warn("Unknown value type: " + string);
			}
		}
		return values;
	}

	private static Set<LogEntryValue> calculateRequiredLogEntryValues(final List<Value> values) {
		Set<LogEntryValue> logEntryValues = EnumSet.noneOf(LogEntryValue.class);
		for (Value value : values) {
			logEntryValues.add(value.requiredLogEntryValue);
		}
		return logEntryValues;
	}

	private static String renderSql(final Connection connection, final String table, final List<Value> values) throws SQLException {
		StringBuilder builder = new StringBuilder();

		builder.append("INSERT INTO ");

		String quote = connection.getMetaData().getIdentifierQuoteString();
		if (quote == null || quote.trim().isEmpty()) {
			for (int i = 0; i < table.length(); ++i) {
				char c = table.charAt(i);
				if (!Character.isLetterOrDigit(c) && c != '_' && c != '@' && c != '$' && c != '#') {
					throw new SQLException("Illegal table name: " + table);
				}
			}
			builder.append(table);
		} else {
			for (int i = 0; i < table.length(); ++i) {
				char c = table.charAt(i);
				if (c == '\n' || c == '\r') {
					throw new SQLException("Table name contains line breaks: " + table);
				}
			}
			builder.append(quote).append(table.replaceAll(Pattern.quote(quote), quote + quote)).append(quote);
		}

		builder.append(" VALUES (");

		for (int i = 0; i < values.size(); ++i) {
			if (i > 0) {
				builder.append(", ?");
			} else {
				builder.append("?");
			}
		}

		builder.append(")");

		return builder.toString();
	}

	private static String getNameOfClass(final String fullyQualifiedClassName) {
		int dotIndex = fullyQualifiedClassName.lastIndexOf('.');
		if (dotIndex < 0) {
			return fullyQualifiedClassName;
		} else {
			return fullyQualifiedClassName.substring(dotIndex + 1);
		}
	}

	private static String getPackageOfClass(final String fullyQualifiedClassName) {
		int dotIndex = fullyQualifiedClassName.lastIndexOf('.');
		if (dotIndex < 0) {
			return "";
		} else {
			return fullyQualifiedClassName.substring(0, dotIndex);
		}
	}

	private static String formatException(final Throwable exception) {
		StringBuilder builder = new StringBuilder();
		formatExceptionWithStackTrace(builder, exception);
		return builder.toString();
	}

	private static void formatExceptionWithStackTrace(final StringBuilder builder, final Throwable exception) {
		builder.append(exception.getClass().getName());

		String message = exception.getMessage();
		if (message != null) {
			builder.append(": ");
			builder.append(message);
		}

		StackTraceElement[] stackTrace = exception.getStackTrace();
		for (int i = 0; i < stackTrace.length; ++i) {
			builder.append(NEW_LINE);
			builder.append('\t');
			builder.append("at ");
			builder.append(stackTrace[i]);
		}

		Throwable cause = exception.getCause();
		if (cause != null) {
			builder.append(NEW_LINE);
			builder.append("Caused by: ");
			formatExceptionWithStackTrace(builder, cause);
		}
	}

	private static void fillStatement(final PreparedStatement statement, final List<Value> values, final LogEntry logEntry) throws SQLException {
		for (int i = 0; i < values.size(); ++i) {
			switch (values.get(i)) {
				case DATE:
					statement.setTimestamp(i + 1, new Timestamp(logEntry.getDate().getTime()));
					break;
				case PROCESS_ID:
					statement.setString(i + 1, logEntry.getProcessId());
					break;
				case THREAD_NAME:
					statement.setString(i + 1, logEntry.getThread().getName());
					break;
				case THREAD_ID:
					statement.setLong(i + 1, logEntry.getThread().getId());
					break;
				case CLASS:
					statement.setString(i + 1, logEntry.getClassName());
					break;
				case CLASS_NAME:
					statement.setString(i + 1, getNameOfClass(logEntry.getClassName()));
					break;
				case PACKAGE:
					statement.setString(i + 1, getPackageOfClass(logEntry.getClassName()));
					break;
				case METHOD:
					if (logEntry.getMethodName() == null) {
						statement.setNull(i + 1, Types.VARCHAR);
					} else {
						statement.setString(i + 1, logEntry.getMethodName());
					}
					break;
				case FILE:
					if (logEntry.getFilename() == null) {
						statement.setNull(i + 1, Types.VARCHAR);
					} else {
						statement.setString(i + 1, logEntry.getFilename());
					}
					break;
				case LINE_NUMBER:
					if (logEntry.getLineNumber() < 0) {
						statement.setNull(i + 1, Types.VARCHAR);
					} else {
						statement.setInt(i + 1, logEntry.getLineNumber());
					}
					break;
				case LOGGING_LEVEL:
					statement.setString(i + 1, logEntry.getLoggingLevel().name());
					break;
				case MESSAGE:
					if (logEntry.getMessage() == null) {
						statement.setNull(i + 1, Types.VARCHAR);
					} else {
						statement.setString(i + 1, logEntry.getMessage());
					}
					break;
				case EXCEPTION:
					if (logEntry.getException() == null) {
						statement.setNull(i + 1, Types.VARCHAR);
					} else {
						statement.setString(i + 1, formatException(logEntry.getException()));
					}
					break;
				case RENDERED_LOG_ENTRY:
					statement.setString(i + 1, logEntry.getRenderedLogEntry());
					break;
				default:
					InternalLogger.warn("Unknown value type: " + values.get(i));
					break;
			}
		}
	}

	private void executeBatch() throws SQLException {
		try {
			batchStatement.executeBatch();
			batchCount = 0;
			batchStatement.close();
		} finally {
			batchStatement = connection.prepareStatement(sql);
		}
	}

	/**
	 * Values to insert.
	 */
	public static enum Value {

		/**
		 * The current date
		 * 
		 * @see Date
		 */
		DATE(LogEntryValue.DATE),

		/**
		 * The ID of the process (pid)
		 */
		PROCESS_ID(LogEntryValue.PROCESS_ID),

		/**
		 * The name of the current thread
		 */
		THREAD_NAME(LogEntryValue.THREAD),

		/**
		 * The ID of the current thread
		 */
		THREAD_ID(LogEntryValue.THREAD),

		/**
		 * The fully qualified class name of the caller
		 */
		CLASS(LogEntryValue.CLASS),

		/**
		 * The class name without package of the caller
		 */
		CLASS_NAME(LogEntryValue.CLASS),

		/**
		 * The package name of the caller
		 */
		PACKAGE(LogEntryValue.CLASS),

		/**
		 * The method name of the caller
		 */
		METHOD(LogEntryValue.METHOD),

		/**
		 * The source filename of the caller
		 */
		FILE(LogEntryValue.FILE),

		/**
		 * The line number of calling
		 */
		LINE_NUMBER(LogEntryValue.LINE_NUMBER),

		/**
		 * The logging level
		 * 
		 * @see LoggingLevel
		 */
		LOGGING_LEVEL(LogEntryValue.LOGGING_LEVEL),

		/**
		 * The message of the logging event
		 */
		MESSAGE(LogEntryValue.MESSAGE),

		/**
		 * The exception of the log entry
		 * 
		 * @see Throwable
		 */
		EXCEPTION(LogEntryValue.EXCEPTION),

		/**
		 * The rendered log entry
		 */
		RENDERED_LOG_ENTRY(LogEntryValue.RENDERED_LOG_ENTRY);

		private final LogEntryValue requiredLogEntryValue;

		private Value(final LogEntryValue requiredLogEntryValue) {
			this.requiredLogEntryValue = requiredLogEntryValue;
		}

	}

}