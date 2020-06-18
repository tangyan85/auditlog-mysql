package com.ruoyi.interceptor.handler;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.util.StringUtils;
import com.ruoyi.domain.AuditLog;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class MySqlInsertSQLAuditHandler extends AbstractSQLAuditHandler
{

    private String table;

    private List<String> columnList = new ArrayList<>();

    private Boolean preHandled = Boolean.FALSE;

    public MySqlInsertSQLAuditHandler(Connection connection, DBMetaDataHolder dbMetaDataHolder, Method clerkIdMethod, String insertSQL)
    {
        super(connection, dbMetaDataHolder, clerkIdMethod, insertSQL);
    }

    @Override
    protected SQLStatement parseSQLStatement(SQLStatementParser statementParser)
    {
        return statementParser.parseInsert();
    }

    @Override
    protected SQLTableSource getMajorTableSource(SQLStatement statement)
    {
        if (statement instanceof MySqlInsertStatement)
            return ((MySqlInsertStatement) statement).getTableSource();
        else
            return null;
    }

    @Override
    public void preHandle()
    {
        if (getSqlStatement() instanceof MySqlInsertStatement)
        {
            MySqlInsertStatement sqlInsertStatement = (MySqlInsertStatement) getSqlStatement();
            if (sqlInsertStatement.getColumns().size() > 0)
            {
                SQLExpr sqlExpr = sqlInsertStatement.getColumns().get(0);
                String[] aliasAndColumn = separateAliasAndColumn(SQLUtils.toMySqlString(sqlExpr));
                if (aliasAndColumn[0] != null)
                {
                    table = getAliasToTableMap().get(aliasAndColumn[0]);
                } else if (getTables().size() == 1)
                {
                    table = getTables().get(0);
                } else
                {
                    table = determineTableForColumn(aliasAndColumn[1]);
                }
                if(StringUtils.isEmpty(table)){
                	System.err.println("Error data at table:null at preHandle:skip!!!!!");
                	return;
                }
                for (int i = 0; i < sqlInsertStatement.getColumns().size(); i++)
                {
                    SQLExpr columnExpr = sqlInsertStatement.getColumns().get(i);
                    columnList.add(separateAliasAndColumn(SQLUtils.toMySqlString(columnExpr))[1]);
                }
            }
            preHandled = Boolean.TRUE;
        }
    }

    @Override
    public void postHandle()
    {
        if(StringUtils.isEmpty(table)){
        	System.err.println("Error data at table:null at postHandle:skip!!!!!");
        	return;
        }
        if (preHandled)
        {
            List<List<AuditLog>> auditLogs = new ArrayList<>();
            Statement statement = null;
            try
            {   
            	//1：取max（id）?
            	//todo:如果是复核主键会有问题
            	String PrimaryKeys=getDbMetaDataHolder().getPrimaryKeys().get(table);
                statement = getConnection().createStatement();
                //ResultSet limitResultSet = statement.executeQuery("SELECT rowno - 1, rowcon FROM (SELECT @rowno := @rowno + 1 AS rowno, t2.rowcon AS rowcon, ID FROM " + table + " r, (SELECT @rowno := 0) t, (SELECT ROW_COUNT() AS rowcon) t2 order by r.id asc) b WHERE b.ID = (SELECT LAST_INSERT_ID())");
                ResultSet limitResultSet = statement.executeQuery(String.format("SELECT rowno - 1, rowcon FROM (SELECT @rowno := @rowno + 1 AS rowno, t2.rowcon AS rowcon, %s as ID FROM %s r, (SELECT @rowno := 0) t, (SELECT ROW_COUNT() AS rowcon) t2 order by r.%s asc) b WHERE b.ID = (SELECT LAST_INSERT_ID())",PrimaryKeys,table,PrimaryKeys));
                if (limitResultSet.next())
                {
                    Integer limit_1 = limitResultSet.getInt(1);
                    Integer limit_2 = Math.max(limitResultSet.getInt(2),1);
                    StringBuilder sb = new StringBuilder();
                    sb.append(getDbMetaDataHolder().getPrimaryKeys().get(table));
                    for (String column : columnList)
                    {
                        sb.append(", ");
                        sb.append(column);
                    }
                    ResultSet resultSet = statement.executeQuery(String.format("select %s from %s where %s>=(select %s from %s order by %s asc limit %s,1) limit %s", sb.toString(), table,PrimaryKeys,PrimaryKeys, table,PrimaryKeys, limit_1, limit_2));
                    int columnCount = resultSet.getMetaData().getColumnCount();
                    while (resultSet.next())
                    {
                        List<AuditLog> list = new ArrayList<>();
                        Object primaryKey = null;
                        for (int i = 1; i < columnCount + 1; i++)
                        {
                            if (i == 1)
                            {
                                primaryKey = resultSet.getObject(i);
                            } else
                            {
                                AuditLog auditLog = new AuditLog(table, columnList.get(i - 2), null, primaryKey, AuditLog.OperationEnum.insert.name(), null, resultSet.getObject(i));
                                list.add(auditLog);
                            }
                        }
                        auditLogs.add(list);
                    }
                    resultSet.close();
                }
                limitResultSet.close();
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
            saveAuditLog(auditLogs);
        }
    }

}
