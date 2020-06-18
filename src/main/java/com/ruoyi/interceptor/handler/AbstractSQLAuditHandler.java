package com.ruoyi.interceptor.handler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.ruoyi.domain.AuditLog;

abstract class AbstractSQLAuditHandler extends AbstractSQLHandler
{

    private final String auditLogInsertSQL = "insert into %s " +
            "(TableName, ColumnName, PrimaryKey, ParentID, NewValue, OldValue, Operation, CreateTime, CreateClerk) " +
            "values(?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final Object noClerkId = -1L;

    private Date insertTime = null;

    private DBMetaDataHolder dbMetaDataHolder;

    private Method clerkIdMethod;

    AbstractSQLAuditHandler(Connection connection, DBMetaDataHolder dbMetaDataHolder, Method clerkIdMethod, String sql)
    {
        super(connection, sql);
        this.dbMetaDataHolder = dbMetaDataHolder;
        this.clerkIdMethod = clerkIdMethod;
    }

    void saveAuditLog(List<List<AuditLog>> auditLogList)
    {
        try
        {
            boolean autoCommit = getConnection().getAutoCommit();
            if (autoCommit)
            {
                getConnection().setAutoCommit(false);
            }
            insertTime = new Date();
            for (List<AuditLog> auditLogs : auditLogList)
            {
                if (auditLogs.size() > 0)
                {
                    Object parentID = saveAuditLog(auditLogs.get(0), null);
                    auditLogs.remove(0);
                    for (AuditLog auditLog : auditLogs)
                    {
                        saveAuditLog(auditLog, parentID);
                    }
                }
            }
            if (autoCommit)
            {
                getConnection().commit();
                getConnection().setAutoCommit(true);
            }
        } catch (SQLException e)
        {
            e.printStackTrace();
        }
    }

    void saveAuditLog(Map<String, List<List<AuditLog>>> auditLogListMap)
    {
        List<List<AuditLog>> auditLogList = new ArrayList<>();
        for (List<List<AuditLog>> lists : auditLogListMap.values())
        {
            auditLogList.addAll(lists);
        }
        saveAuditLog(auditLogList);
    }

    private Object saveAuditLog(AuditLog auditLog, Object parentID)
    {
        Object resultId = null;
        PreparedStatement preparedStatement = null;
        try
        {

            if (parentID == null)
            {
                preparedStatement = getConnection().prepareStatement(String.format(auditLogInsertSQL,
                        dbMetaDataHolder.getAuditLogTableCreator().getCurrentValidTableName()),
                        Statement.RETURN_GENERATED_KEYS);
            } else
            {
                preparedStatement = getConnection().prepareStatement(String.format(auditLogInsertSQL,
                        dbMetaDataHolder.getAuditLogTableCreator().getCurrentValidTableName()));
            }

            int i = 1;
            preparedStatement.setObject(i++, auditLog.getTableName());
            preparedStatement.setObject(i++, auditLog.getColumnName());
            preparedStatement.setObject(i++, auditLog.getPrimaryKey());
            preparedStatement.setObject(i++, parentID);
            preparedStatement.setObject(i++, auditLog.getNewValue());
            preparedStatement.setObject(i++, auditLog.getOldValue());
            preparedStatement.setObject(i++, auditLog.getOperation());
            preparedStatement.setObject(i++, insertTime);
            preparedStatement.setObject(i, noClerkId);
            try
            {
                if (clerkIdMethod != null)
                    preparedStatement.setObject(i, clerkIdMethod.invoke(null, null));
            } catch (RuntimeException | IllegalAccessException | InvocationTargetException e)
            {
                // do nothing.
            }
            int affectRows = preparedStatement.executeUpdate();
            if (affectRows > 0 && parentID == null)
            {
            	//oracle：  这里需要怎么做？
                ResultSet resultSet = preparedStatement.getGeneratedKeys();
                if (resultSet.next())
                    resultId = resultSet.getObject(1);
                resultSet.close();
            }
        } catch (SQLException e)
        {
            e.printStackTrace();
        } finally
        {
            if (preparedStatement != null)
            {
                try
                {
                    preparedStatement.close();
                } catch (SQLException e)
                {
                    e.printStackTrace();
                }
            }
        }
        return resultId;
    }

    DBMetaDataHolder getDbMetaDataHolder()
    {
        return dbMetaDataHolder;
    }

    public Method getClerkIdMethod()
    {
        return clerkIdMethod;
    }
}