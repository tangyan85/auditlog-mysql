package com.ruoyi.interceptor.handler;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLOrderBy;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.ruoyi.domain.AuditLog;

import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.apache.commons.lang.StringUtils;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MySqlUpdateSQLAuditHandler extends AbstractSQLAuditHandler
{

    private Map<String, List<String>> updateColumnListMap;

    private Map<String, Map<Object, Object[]>> rowsBeforeUpdateListMap;

    private Boolean preHandled = Boolean.FALSE;

    public MySqlUpdateSQLAuditHandler(Connection connection, DBMetaDataHolder dbMetaDataHolder, Method clerkIdMethod, String updateSQL)
    {
        super(connection, dbMetaDataHolder, clerkIdMethod, updateSQL);
    }

    @Override
    protected SQLTableSource getMajorTableSource(SQLStatement statement)
    {
        if (statement instanceof MySqlUpdateStatement)
            return ((MySqlUpdateStatement) statement).getTableSource();
        else
            return null;
    }

    @Override
    protected SQLStatement parseSQLStatement(SQLStatementParser statementParser)
    {
        return statementParser.parseUpdateStatement();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void preHandle()
    {
        if (getSqlStatement() instanceof MySqlUpdateStatement)
        {
            MySqlUpdateStatement updateStatement = (MySqlUpdateStatement) getSqlStatement();
            SQLTableSource tableSource = updateStatement.getTableSource();
            List<SQLUpdateSetItem> updateSetItems = updateStatement.getItems();
            SQLExpr where = updateStatement.getWhere();
            SQLOrderBy orderBy = updateStatement.getOrderBy();
            SQLLimit limit = updateStatement.getLimit();
            updateColumnListMap = new CaseInsensitiveMap();
            for (SQLUpdateSetItem sqlUpdateSetItem : updateSetItems)
            {
                String aliasAndColumn[] = separateAliasAndColumn(SQLUtils.toMySqlString(sqlUpdateSetItem.getColumn()));
                String alias = aliasAndColumn[0];
                String column = aliasAndColumn[1];
                if (StringUtils.isNotBlank(alias))
                {
                    String tableName = getAliasToTableMap().get(alias);
                    if (StringUtils.isNotBlank(tableName))
                    {
                        List<String> columnList = updateColumnListMap.get(tableName);
                        if (columnList == null)
                            columnList = new ArrayList<>();
                        columnList.add(column);
                        updateColumnListMap.put(tableName, columnList);
                    }
                } else if (getTables().size() == 1)
                {
                    String tableName = getTables().get(0);
                    if (StringUtils.isNotBlank(tableName))
                    {
                        List<String> columnList = updateColumnListMap.get(tableName);
                        if (columnList == null)
                            columnList = new ArrayList<>();
                        columnList.add(column);
                        updateColumnListMap.put(tableName, columnList);
                    }
                } else
                {
                    String tableName = determineTableForColumn(column);
                    if (StringUtils.isNotBlank(tableName))
                    {
                        List<String> columnList = updateColumnListMap.get(tableName);
                        if (columnList == null)
                            columnList = new ArrayList<>();
                        columnList.add(column);
                        updateColumnListMap.put(tableName, columnList);
                    }
                }
            }
            MySqlSelectQueryBlock selectQueryBlock = new MySqlSelectQueryBlock();
            selectQueryBlock.setFrom(tableSource);
            selectQueryBlock.setWhere(where);
            selectQueryBlock.setOrderBy(orderBy);
            selectQueryBlock.setLimit(limit);
            for (Map.Entry<String, List<String>> updateInfoListEntry : updateColumnListMap.entrySet())
            {
            	//todo:bug  PrimaryKeys is wrong!;;
                selectQueryBlock.getSelectList().add(new SQLSelectItem(SQLUtils.toSQLExpr(
                        String.format("%s.%s", getTableToAliasMap().get(updateInfoListEntry.getKey()),
                                getDbMetaDataHolder().getPrimaryKeys().get(updateInfoListEntry.getKey())))));
                for (String column : updateInfoListEntry.getValue())
                {
                    selectQueryBlock.getSelectList().add(new SQLSelectItem(SQLUtils.toSQLExpr(
                            String.format("%s.%s", getTableToAliasMap().get(updateInfoListEntry.getKey()), column))));
                }
            }
            rowsBeforeUpdateListMap = getTablesData(trimSQLWhitespaces(SQLUtils.toMySqlString(selectQueryBlock)), updateColumnListMap);
            preHandled = Boolean.TRUE;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void postHandle()
    {
        if (preHandled)
        {
            Map<String, List<List<AuditLog>>> auditLogListMap = new CaseInsensitiveMap();
            if (rowsBeforeUpdateListMap != null)
            {
                Map<String, Map<Object, Object[]>> rowsAfterUpdateListMap = getTablesDataAfterUpdate();
                for (String tableName : rowsBeforeUpdateListMap.keySet())
                {
                    Map<Object, Object[]> rowsBeforeUpdateRowsMap = rowsBeforeUpdateListMap.get(tableName);
                    Map<Object, Object[]> rowsAfterUpdateRowsMap = rowsAfterUpdateListMap.get(tableName);
                    if (rowsBeforeUpdateRowsMap != null && rowsAfterUpdateRowsMap != null)
                    {
                        List<List<AuditLog>> rowList = auditLogListMap.get(tableName);
                        if (rowList == null) rowList = new ArrayList<>();
                        for (Object pKey : rowsBeforeUpdateRowsMap.keySet())
                        {
                            Object[] rowBeforeUpdate = rowsBeforeUpdateRowsMap.get(pKey);
                            Object[] rowAfterUpdate = rowsAfterUpdateRowsMap.get(pKey);
                            List<AuditLog> colList = new ArrayList<>();
                            for (int col = 0; col < rowBeforeUpdate.length; col++)
                            {
                                if (rowBeforeUpdate[col] != null && !rowBeforeUpdate[col].equals(rowAfterUpdate[col])
                                        || rowBeforeUpdate[col] == null && rowAfterUpdate[col] != null)
                                {
                                    colList.add(new AuditLog(tableName, updateColumnListMap.get(tableName).get(col), null,
                                            pKey, AuditLog.OperationEnum.update.name(), rowBeforeUpdate[col], rowAfterUpdate[col]));
                                }
                            }
                            if (colList.size() > 0)
                                rowList.add(colList);
                        }
                        if (rowList.size() > 0)
                            auditLogListMap.put(tableName, rowList);
                    }
                }
            }
            saveAuditLog(auditLogListMap);
        }
    }


    @SuppressWarnings("unchecked")
    private Map<String, Map<Object, Object[]>> getTablesDataAfterUpdate()
    {
        Map<String, Map<Object, Object[]>> resultListMap = new CaseInsensitiveMap();
        for (Map.Entry<String, Map<Object, Object[]>> tableDataEntry : rowsBeforeUpdateListMap.entrySet())
        {
            String tableName = tableDataEntry.getKey();
            MySqlSelectQueryBlock selectQueryBlock = new MySqlSelectQueryBlock();
            selectQueryBlock.getSelectList().add(new SQLSelectItem(SQLUtils.toSQLExpr(getDbMetaDataHolder().getPrimaryKeys().get(tableName))));
            for (String column : updateColumnListMap.get(tableName))
            {
                selectQueryBlock.getSelectList().add(new SQLSelectItem(SQLUtils.toSQLExpr(column)));
            }
            selectQueryBlock.setFrom(new SQLExprTableSource(new SQLIdentifierExpr(tableName)));
            SQLInListExpr sqlInListExpr = new SQLInListExpr();
            List<SQLExpr> sqlExprList = new ArrayList<>();
            for (Object primaryKey : tableDataEntry.getValue().keySet())
            {
                sqlExprList.add(SQLUtils.toSQLExpr(primaryKey.toString()));
            }
            sqlInListExpr.setExpr(new SQLIdentifierExpr(getDbMetaDataHolder().getPrimaryKeys().get(tableName)));
            sqlInListExpr.setTargetList(sqlExprList);
            selectQueryBlock.setWhere(sqlInListExpr);
            Map<String, List<String>> tableColumnMap = new CaseInsensitiveMap();
            tableColumnMap.put(tableName, updateColumnListMap.get(tableName));
            Map<String, Map<Object, Object[]>> map = getTablesData(trimSQLWhitespaces(SQLUtils.toMySqlString(selectQueryBlock)), tableColumnMap);
            resultListMap.putAll(map);
        }
        return resultListMap;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<Object, Object[]>> getTablesData(String querySQL, Map<String, List<String>> tableColumnsMap)
    {
        Map<String, Map<Object, Object[]>> resultListMap = new CaseInsensitiveMap();
        PreparedStatement statement = null;
        try
        {
            statement = getConnection().prepareStatement(querySQL);
            ResultSet resultSet = statement.executeQuery();
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next())
            {
                Map<String, Object> currRowTablePKeyMap = new CaseInsensitiveMap();
                for (int i = 1; i < columnCount + 1; i++)
                {
                    String currentTableName = resultSet.getMetaData().getTableName(i);
                    if (StringUtils.isNotBlank(currentTableName))
                    {
                        if (currRowTablePKeyMap.get(currentTableName) == null)
                        {
                            currRowTablePKeyMap.put(currentTableName, resultSet.getObject(i));
                        } else
                        {
                            Map<Object, Object[]> rowsMap = resultListMap.get(currentTableName);
                            if (rowsMap == null)
                                rowsMap = new CaseInsensitiveMap();
                            Object[] rowData = rowsMap.get(currRowTablePKeyMap.get(currentTableName));
                            if (rowData == null)
                                rowData = new Object[]{};
                            if (rowData.length < tableColumnsMap.get(currentTableName).size())
                            {
                                rowData = Arrays.copyOf(rowData, rowData.length + 1);
                                rowData[rowData.length - 1] = resultSet.getObject(i);
                            }
                            rowsMap.put(currRowTablePKeyMap.get(currentTableName), rowData);
                            resultListMap.put(currentTableName, rowsMap);
                        }
                    }
                }
            }
            resultSet.close();
        } catch (SQLException e)
        {
            e.printStackTrace();
        } finally
        {
            if (statement != null)
            {
                try
                {
                    statement.close();
                } catch (SQLException e)
                {
                    e.printStackTrace();
                }
            }
        }
        return resultListMap;
    }

}
