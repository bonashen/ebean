package com.avaje.ebean.dbmigration.ddlgeneration.platform;

import com.avaje.ebean.config.DbConstraintNaming;
import com.avaje.ebean.config.NamingConvention;
import com.avaje.ebean.config.ServerConfig;
import com.avaje.ebean.config.dbplatform.IdType;
import com.avaje.ebean.dbmigration.ddlgeneration.DdlBuffer;
import com.avaje.ebean.dbmigration.ddlgeneration.DdlWrite;
import com.avaje.ebean.dbmigration.ddlgeneration.TableDdl;
import com.avaje.ebean.dbmigration.ddlgeneration.platform.util.IndexSet;
import com.avaje.ebean.dbmigration.migration.AddColumn;
import com.avaje.ebean.dbmigration.migration.AddHistoryTable;
import com.avaje.ebean.dbmigration.migration.AlterColumn;
import com.avaje.ebean.dbmigration.migration.Column;
import com.avaje.ebean.dbmigration.migration.CreateTable;
import com.avaje.ebean.dbmigration.migration.DropColumn;
import com.avaje.ebean.dbmigration.migration.DropHistoryTable;
import com.avaje.ebean.dbmigration.migration.DropTable;
import com.avaje.ebean.dbmigration.migration.ForeignKey;
import com.avaje.ebean.dbmigration.model.MTable;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Base implementation for 'create table' and 'alter table' statements.
 */
public class BaseTableDdl implements TableDdl {

  protected final DbConstraintNaming naming;

  protected final NamingConvention namingConvention;

  protected final PlatformDdl platformDdl;

  private final String historyTableSuffix;

  /**
   * Used to check that indexes on foreign keys should be skipped as a unique index on the columns
   * already exists.
   */
  protected IndexSet indexSet = new IndexSet();

  /**
   * Used when unique constraints specifically for OneToOne can't be created normally (MsSqlServer).
   */
  protected List<Column> externalUnique = new ArrayList<Column>();

  // counters used when constraint names are truncated due to maximum length
  // and these counters are used to keep the constraint name unique
  protected int countCheck;
  protected int countUnique;
  protected int countForeignKey;
  protected int countIndex;

  /**
   * Base tables that have associated history tables that need their triggers
   * regenerated as columns have been added or removed.
   */
  protected Set<String> regenerateHistoryTriggers = new LinkedHashSet<String>();

  /**
   * Construct with a naming convention and platform specific DDL.
   */
  public BaseTableDdl(ServerConfig serverConfig, PlatformDdl platformDdl) {
    this.namingConvention = serverConfig.getNamingConvention();
    this.naming = serverConfig.getConstraintNaming();
    this.platformDdl = platformDdl;
    this.historyTableSuffix = serverConfig.getHistoryTableSuffix();
  }

  /**
   * Reset counters and index set for each table processed.
   */
  protected void reset() {
    indexSet.clear();
    externalUnique.clear();
    countCheck = 0;
    countUnique = 0;
    countForeignKey = 0;
    countIndex = 0;
  }

  /**
   * Generate the appropriate 'create table' and matching 'drop table' statements
   * and add them to the appropriate 'apply' and 'rollback' buffers.
   */
  @Override
  public void generate(DdlWrite writer, CreateTable createTable) throws IOException {

    reset();

    String tableName = lowerName(createTable.getName());
    List<Column> columns = createTable.getColumn();
    List<Column> pk = determinePrimaryKeyColumns(columns);

    boolean singleColumnPrimaryKey = (pk.size() == 1);
    boolean useIdentity = false;
    boolean useSequence = false;

    if (singleColumnPrimaryKey) {
      IdType useDbIdentityType = platformDdl.useIdentityType(createTable.getIdentityType());
      useIdentity = (IdType.IDENTITY == useDbIdentityType);
      useSequence = (IdType.SEQUENCE == useDbIdentityType);
    }

    DdlBuffer apply = writer.apply();
    apply.append("create table ").append(tableName).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      apply.newLine();
      writeColumnDefinition(apply, columns.get(i), useIdentity);
      if (i < columns.size() - 1) {
        apply.append(",");
      }
    }

    writeCheckConstraints(apply, createTable);
    writeUniqueConstraints(apply, createTable);
    writeCompoundUniqueConstraints(apply, createTable);
    if (!pk.isEmpty()) {
      // defined on the columns
      writePrimaryKeyConstraint(apply, createTable.getPkName(), toColumnNames(pk));
    }

    apply.newLine().append(")").endOfStatement();

    writeUniqueOneToOneConstraints(writer, createTable);

    if (isTrue(createTable.isWithHistory())) {
      // create history with rollback before the
      // associated drop table is written to rollback
      createWithHistory(writer, createTable.getName());
    }

    // add drop table to the rollback buffer - do this before
    // we drop the related sequence (if sequences are used)
    dropTable(writer.rollback(), tableName);

    if (useSequence) {
      String pkCol = pk.get(0).getName();
      writeSequence(writer, createTable, pkCol);
    }

    // add blank line for a bit of whitespace between tables
    apply.end();
    writer.rollback().end();

    writeAddForeignKeys(writer, createTable);

  }

  /**
   * Specific handling of OneToOne unique constraints for MsSqlServer.
   * For all other DB platforms these unique constraints are done inline as per normal.
   */
  private void writeUniqueOneToOneConstraints(DdlWrite write, CreateTable createTable) throws IOException {

    String tableName = createTable.getName();
    for (Column col : externalUnique) {
      String uqName = col.getUniqueOneToOne();
      String[] columnNames = {col.getName()};
      write.apply()
          .append(platformDdl.createExternalUniqueForOneToOne(uqName, tableName, columnNames))
          .endOfStatement();

      write.rollbackForeignKeys()
          .append(platformDdl.dropIndex(uqName, tableName))
          .endOfStatement();
    }
  }

  private void writeSequence(DdlWrite writer, CreateTable createTable, String pk) throws IOException {

    // explicit sequence use or platform decides
    String explicitSequenceName = createTable.getSequenceName();
    int initial = toInt(createTable.getSequenceInitial());
    int allocate = toInt(createTable.getSequenceAllocate());

    String seqName = explicitSequenceName;
    if (seqName == null) {
      seqName = namingConvention.getSequenceName(createTable.getName(), pk);
    }

    String createSeq = platformDdl.createSequence(seqName, initial, allocate);
    if (createSeq != null) {
      writer.apply().append(createSeq).newLine();
      writer.rollback().append(platformDdl.dropSequence(seqName)).endOfStatement();
    }
  }

  private void createWithHistory(DdlWrite writer, String name) throws IOException {

    MTable table = writer.getTable(name);
    platformDdl.createWithHistory(writer, table);
  }

  protected void writeAddForeignKeys(DdlWrite write, CreateTable createTable) throws IOException {

    String tableName = createTable.getName();
    List<Column> columns = createTable.getColumn();
    for (Column column : columns) {
      String references = column.getReferences();
      if (hasValue(references)) {
        writeForeignKey(write, tableName, column);
      }
    }

    writeAddCompoundForeignKeys(write, createTable);
  }

  protected void writeAddCompoundForeignKeys(DdlWrite write, CreateTable createTable) throws IOException {

    String tableName = createTable.getName();

    List<ForeignKey> foreignKey = createTable.getForeignKey();
    for (ForeignKey key : foreignKey) {

      String refTableName = key.getRefTableName();
      String fkName = key.getName();
      String[] cols = toColumnNamesSplit(key.getColumnNames());
      String[] refColumns = toColumnNamesSplit(key.getRefColumnNames());

      writeForeignKey(write, fkName, tableName, cols, refTableName, refColumns, key.getIndexName());
    }
  }

  protected void writeForeignKey(DdlWrite write, String tableName, Column column) throws IOException {

    String fkName = column.getForeignKeyName();
    String references = column.getReferences();
    int pos = references.lastIndexOf('.');
    if (pos == -1) {
      throw new IllegalStateException("Expecting period '.' character for table.column split but not found in [" + references + "]");
    }
    String refTableName = references.substring(0, pos);
    String refColumnName = references.substring(pos + 1);

    String[] cols = {column.getName()};
    String[] refCols = {refColumnName};

    writeForeignKey(write, fkName, tableName, cols, refTableName, refCols, column.getForeignKeyIndex());
  }

  protected void writeForeignKey(DdlWrite write, String fkName, String tableName, String[] columns, String refTable, String[] refColumns, String indexName) throws IOException {

    tableName = lowerName(tableName);
    DdlBuffer fkeyBuffer = write.applyForeignKeys();
    alterTableAddForeignKey(fkeyBuffer, fkName, tableName, columns, refTable, refColumns);

    if (indexName != null) {
      // no matching unique constraint so add the index
      fkeyBuffer.append("create index ").append(indexName).append(" on ").append(tableName);
      appendColumns(columns, fkeyBuffer);
      fkeyBuffer.endOfStatement();
    }

    fkeyBuffer.end();

    write.rollbackForeignKeys()
        .append(platformDdl.alterTableDropForeignKey(tableName, fkName))
        .endOfStatement();

    if (indexName != null) {
      write.rollbackForeignKeys()
          .append(platformDdl.dropIndex(indexName, tableName))
          .endOfStatement();
    }

    write.rollbackForeignKeys().end();

  }

  protected void alterTableAddForeignKey(DdlBuffer buffer, String fkName, String tableName, String[] columns, String refTable, String[] refColumns) throws IOException {

    buffer
        .append("alter table ").append(tableName)
        .append(" add constraint ").append(fkName)
        .append(" foreign key");
    appendColumns(columns, buffer);
    buffer
        .append(" references ")
        .append(lowerName(refTable));
    appendColumns(refColumns, buffer);
    buffer.appendWithSpace(platformDdl.getForeignKeyRestrict())
        .endOfStatement();
  }

  private void appendColumns(String[] columns, DdlBuffer buffer) throws IOException {
    buffer.append(" (");
    for (int i = 0; i < columns.length; i++) {
      if (i > 0) {
        buffer.append(",");
      }
      buffer.append(lowerName(columns[i].trim()));
    }
    buffer.append(")");
  }


  /**
   * Add 'drop table' statement to the buffer.
   */
  protected void dropTable(DdlBuffer buffer, String tableName) throws IOException {

    buffer.append(platformDdl.dropTable(tableName)).endOfStatement();
  }

  /**
   * Write all the check constraints.
   */
  protected void writeCheckConstraints(DdlBuffer apply, CreateTable createTable) throws IOException {

    List<Column> columns = createTable.getColumn();
    for (Column column : columns) {
      String checkConstraint = column.getCheckConstraint();
      if (hasValue(checkConstraint)) {
        writeCheckConstraint(apply, column, checkConstraint);
      }
    }
  }

  /**
   * Write a check constraint.
   */
  protected void writeCheckConstraint(DdlBuffer buffer, Column column, String checkConstraint) throws IOException {

    String ckName = column.getCheckConstraintName();

    buffer.append(",").newLine();
    buffer.append("  constraint ").append(ckName);
    buffer.append(" ").append(checkConstraint);
  }

  protected void writeCompoundUniqueConstraints(DdlBuffer apply, CreateTable createTable) {
    //TODO: Write compound unique constraints
  }

  /**
   * Write the unique constraints inline with the create table statement.
   */
  protected void writeUniqueConstraints(DdlBuffer apply, CreateTable createTable) throws IOException {

    boolean inlineUniqueOneToOne = platformDdl.isInlineUniqueOneToOne();

    List<Column> columns = createTable.getColumn();
    for (Column column : columns) {
      if (hasValue(column.getUnique()) || (inlineUniqueOneToOne && hasValue(column.getUniqueOneToOne()))) {
        // normal mechanism for adding unique constraint
        inlineUniqueConstraintSingle(apply, column);

      } else if (!inlineUniqueOneToOne && hasValue(column.getUniqueOneToOne())) {
        // MsSqlServer specific mechanism for adding unique constraints (that allow nulls)
        externalUnique.add(column);
      }
    }
  }

  /**
   * Write the unique constraint inline with the create table statement.
   */
  protected void inlineUniqueConstraintSingle(DdlBuffer buffer, Column column) throws IOException {

    String uqName = column.getUnique();
    if (uqName == null) {
      uqName = column.getUniqueOneToOne();
    }

    buffer.append(",").newLine();
    buffer.append("  constraint ").append(uqName).append(" unique ");
    buffer.append("(");
    buffer.append(lowerName(column.getName()));
    buffer.append(")");
  }

  /**
   * Write the primary key constraint inline with the create table statement.
   */
  protected void writePrimaryKeyConstraint(DdlBuffer buffer, String pkName, String[] pkColumns) throws IOException {

    buffer.append(",").newLine();
    buffer.append("  constraint ").append(pkName).append(" primary key");
    appendColumns(pkColumns, buffer);
  }

  /**
   * Return as an array of string column names.
   */
  private String[] toColumnNames(List<Column> columns) {

    String[] cols = new String[columns.size()];
    for (int i = 0; i < cols.length; i++) {
      cols[i] = columns.get(i).getName();
    }
    return cols;
  }

  /**
   * Return as an array of string column names.
   */
  private String[] toColumnNamesSplit(String columns) {
    return columns.split(",");
  }

  /**
   * Convert the table or column name to lower case.
   * <p>
   * This is passed up to the platformDdl to override as desired.
   * Generally lower case with underscore is a good cross database
   * choice for column/table names.
   */
  protected String lowerName(String name) {
    return naming.lowerName(name);
  }

  /**
   * Write the column definition to the create table statement.
   */
  protected void writeColumnDefinition(DdlBuffer buffer, Column column, boolean useIdentity) throws IOException {

    boolean identityColumn = useIdentity && isTrue(column.isPrimaryKey());
    String platformType = convertToPlatformType(column.getType(), identityColumn);

    buffer.append("  ");
    buffer.append(lowerName(column.getName()), 30);
    buffer.append(platformType);
    if (isTrue(column.isNotnull()) || isTrue(column.isPrimaryKey())) {
      buffer.append(" not null");
    }

    // add check constraints later as we really want to give them a nice name
    // so that the database can potentially provide a nice SQL error
  }

  /**
   * Convert the expected logical type into a platform specific one.
   * <p>
   * For example clob -> text for postgres.
   * </p>
   */
  protected String convertToPlatformType(String type, boolean identity) {
    return platformDdl.convert(type, identity);
  }

  /**
   * Return the list of columns that make the primary key.
   */
  protected List<Column> determinePrimaryKeyColumns(List<Column> columns) {
    List<Column> pk = new ArrayList<Column>(3);
    for (Column column : columns) {
      if (isTrue(column.isPrimaryKey())) {
        pk.add(column);
      }
    }
    return pk;
  }

  @Override
  public void generate(DdlWrite writer, AddHistoryTable addHistoryTable) throws IOException {
    platformDdl.addHistoryTable(writer, addHistoryTable);
  }

  @Override
  public void generate(DdlWrite writer, DropHistoryTable dropHistoryTable) throws IOException {
    platformDdl.dropHistoryTable(writer, dropHistoryTable);
  }

  @Override
  public void generateExtra(DdlWrite write) throws IOException {
    for (String baseTable : this.regenerateHistoryTriggers) {
      platformDdl.regenerateHistoryTriggers(write, baseTable);
    }
  }

  @Override
  public void generate(DdlWrite writer, AddColumn addColumn) throws IOException {

    String tableName = addColumn.getTableName();
    List<Column> columns = addColumn.getColumn();
    for (Column column : columns) {
      alterTableAddColumn(writer.apply(), tableName, column, false);
      alterTableDropColumn(writer.rollback(), tableName, column.getName());
    }

    if (isTrue(addColumn.isWithHistory())) {
      // make same changes to the history table
      String historyTable = historyTable(tableName);
      regenerateHistoryTriggers(tableName);
      for (Column column : columns) {
        alterTableAddColumn(writer.apply(), historyTable, column, true);
        alterTableDropColumn(writer.rollback(), historyTable, column.getName());
      }
    }

    // add a bit of whitespace
    writer.apply().end();
    writer.rollback().end();
  }

  @Override
  public void generate(DdlWrite writer, DropTable dropTable) throws IOException {

    dropTable(writer.drop(), dropTable.getName());
  }

  @Override
  public void generate(DdlWrite writer, DropColumn dropColumn) throws IOException {

    String tableName = dropColumn.getTableName();

    alterTableDropColumn(writer.drop(), tableName, dropColumn.getColumnName());
    if (isTrue(dropColumn.isWithHistory())) {
      // also drop from the history table
      regenerateHistoryTriggers(tableName);
      alterTableDropColumn(writer.drop(), historyTable(tableName), dropColumn.getColumnName());
    }

    writer.drop().end();
  }

  @Override
  public void generate(DdlWrite writer, AlterColumn alterColumn) throws IOException {

//    if (isTrue(alterColumn.isHistoryExclude())) {
//      historyExcludeColumn(writer, alterColumn);
//    } else if (isFalse(alterColumn.isHistoryExclude())) {
//      historyIncludeColumn(writer, alterColumn);
//    }

    if (hasValue(alterColumn.getDropForeignKey())) {
      alterColumnDropForeignKey(writer, alterColumn);
    }
    if (hasValue(alterColumn.getReferences())) {
      alterColumnAddForeignKey(writer, alterColumn);
    }

    if (hasValue(alterColumn.getDropUnique())) {
      alterColumnDropUniqueConstraint(writer, alterColumn);
    }
    if (hasValue(alterColumn.getUnique())) {
      alterColumnAddUniqueConstraint(writer, alterColumn);
    }
    if (hasValue(alterColumn.getUniqueOneToOne())) {
      alterColumnAddUniqueOneToOneConstraint(writer, alterColumn);
    }

    boolean alterBaseAttributes = false;
    if (hasValue(alterColumn.getType())) {
      alterColumnType(writer, alterColumn);
      alterBaseAttributes = true;
    }
    if (hasValue(alterColumn.getDefaultValue())) {
      alterColumnDefaultValue(writer, alterColumn);
      alterBaseAttributes = true;
    }
    if (alterColumn.isNotnull() != null) {
      alterColumnNotnull(writer, alterColumn);
      alterBaseAttributes = true;
    }

    if (alterBaseAttributes) {
      alterColumnBaseAttributes(writer, alterColumn);
    }
  }

  protected String historyTable(String baseTable) {
    return baseTable + historyTableSuffix;
  }

  /**
   * Register the base table that we need to regenerate the history triggers on.
   */
  protected void regenerateHistoryTriggers(String baseTableName) {
    regenerateHistoryTriggers.add(baseTableName);
  }

  /**
   * This is mysql specific - alter all the base attributes of the column together.
   */
  protected void alterColumnBaseAttributes(DdlWrite writer, AlterColumn alter) throws IOException {

    String ddl = platformDdl.alterColumnBaseAttributes(alter);
    if (hasValue(ddl)) {
      writer.apply().append(ddl).endOfStatement();
      if (isTrue(alter.isWithHistory()) && alter.getType() != null) {
        // mysql and sqlserver column type change allowing nulls in the history table column
        AlterColumn alterHistoryColumn = new AlterColumn();
        alterHistoryColumn.setTableName(historyTable(alter.getTableName()));
        alterHistoryColumn.setColumnName(alter.getColumnName());
        alterHistoryColumn.setType(alter.getType());
        String histColumnDdl = platformDdl.alterColumnBaseAttributes(alterHistoryColumn);
        writer.apply().append(histColumnDdl).endOfStatement();
      }
    }
  }

  protected void alterColumnDefaultValue(DdlWrite writer, AlterColumn alter) throws IOException {

    String ddl = platformDdl.alterColumnDefaultValue(alter.getTableName(), alter.getColumnName(), alter.getDefaultValue());
    if (hasValue(ddl)) {
      writer.apply().append(ddl).endOfStatement();
    }
  }

  protected void alterColumnNotnull(DdlWrite writer, AlterColumn alter) throws IOException {

    String ddl = platformDdl.alterColumnNotnull(alter.getTableName(), alter.getColumnName(), alter.isNotnull());
    if (hasValue(ddl)) {
      writer.apply().append(ddl).endOfStatement();
    }
  }

  protected void alterColumnType(DdlWrite writer, AlterColumn alter) throws IOException {

    String ddl = platformDdl.alterColumnType(alter.getTableName(), alter.getColumnName(), alter.getType());
    if (hasValue(ddl)) {
      writer.apply().append(ddl).endOfStatement();
      if (isTrue(alter.isWithHistory())) {
        // apply same type change to matching column in the history table
        ddl = platformDdl.alterColumnType(historyTable(alter.getTableName()), alter.getColumnName(), alter.getType());
        writer.apply().append(ddl).endOfStatement();
      }
    }
  }


  protected void alterColumnAddForeignKey(DdlWrite writer, AlterColumn alterColumn) throws IOException {

    String tableName = alterColumn.getTableName();
    String fkName = alterColumn.getForeignKeyName();
    String[] cols = {alterColumn.getColumnName()};
    String references = alterColumn.getReferences();
    int pos = references.lastIndexOf('.');
    if (pos == -1) {
      throw new IllegalStateException("Expecting period '.' character for table.column split but not found in [" + references + "]");
    }
    String refTableName = references.substring(0, pos);
    String refColumnName = references.substring(pos + 1);
    String[] refCols = {refColumnName};

    alterTableAddForeignKey(writer.apply(), fkName, tableName, cols, refTableName, refCols);
  }

  protected void alterColumnDropForeignKey(DdlWrite writer, AlterColumn alter) throws IOException {

    writer.apply()
        .append(platformDdl.alterTableDropForeignKey(alter.getTableName(), alter.getDropForeignKey()))
        .endOfStatement();
  }


  protected void alterColumnDropUniqueConstraint(DdlWrite writer, AlterColumn alter) throws IOException {

    writer.apply()
        .append(platformDdl.dropIndex(alter.getDropUnique(), alter.getTableName()))
        .endOfStatement();
  }

  protected void alterColumnAddUniqueOneToOneConstraint(DdlWrite writer, AlterColumn alter) throws IOException {

    addUniqueConstraint(writer, alter, alter.getUniqueOneToOne());
  }

  protected void alterColumnAddUniqueConstraint(DdlWrite writer, AlterColumn alter) throws IOException {

    addUniqueConstraint(writer, alter, alter.getUnique());
  }

  protected void addUniqueConstraint(DdlWrite writer, AlterColumn alter, String uqName) throws IOException {

    String[] cols = {alter.getColumnName()};

    writer.apply()
        .append(platformDdl.createExternalUniqueForOneToOne(uqName, alter.getTableName(), cols))
        .endOfStatement();

    writer.rollbackForeignKeys()
        .append(platformDdl.dropIndex(uqName, alter.getTableName()))
        .endOfStatement();
  }


  protected void alterTableDropColumn(DdlBuffer buffer, String tableName, String columnName) throws IOException {

    buffer.append("alter table ").append(tableName)
        .append(" drop column ").append(columnName)
        .endOfStatement();
  }

  protected void alterTableAddColumn(DdlBuffer buffer, String tableName, Column column, boolean onHistoryTable) throws IOException {

    buffer.append("alter table ").append(tableName)
        .append(" add column ").append(column.getName())
        .append(" ").append(column.getType());

    if (!onHistoryTable) {
      if (isTrue(column.isNotnull())) {
        buffer.append(" not null");
      }
      if (hasValue(column.getCheckConstraint())) {
        buffer.append(" ").append(column.getCheckConstraint());
      }
    }
    buffer.endOfStatement();
  }

  protected boolean isFalse(Boolean value) {
    return value != null && !value;
  }

  /**
   * Return true if null or trimmed string is empty.
   */
  protected boolean hasValue(String value) {
    return value != null && !value.trim().isEmpty();
  }

  /**
   * Null safe Boolean true test.
   */
  protected boolean isTrue(Boolean value) {
    return Boolean.TRUE.equals(value);
  }

  /**
   * Return as an int value with 0 when it is null.
   */
  protected int toInt(BigInteger value) {
    return (value == null) ? 0 : value.intValue();
  }

}