package com.ruoyi.interceptor;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.TypeHandlerRegistry;

import com.ruoyi.domain.AuditLog;
import com.ruoyi.interceptor.handler.*;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Intercepts(
        {
                @Signature(type = Executor.class, method = "update", args = {
                        MappedStatement.class, Object.class
                })
        })
public class SQLAuditLogInterceptor implements Interceptor
{

    private static final Pattern pattern1 = Pattern.compile("\\?(?=\\s*[^']*\\s*,?\\s*(\\w|$))");

    private static final Pattern pattern2 = Pattern.compile("[\\s]+");

    private Boolean auditEnable;

    private DBMetaDataHolder dbMetaDataHolder;

    private Method clerkIdMethod;

    @Override
    public Object intercept(Invocation invocation) throws Throwable
    {
        if (auditEnable && invocation.getArgs()[0] instanceof MappedStatement)
        {
            MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
            String sqlCommandType = mappedStatement.getSqlCommandType().name();
            if (AuditLog.OperationEnum.insert.name().equalsIgnoreCase(sqlCommandType)
                    || AuditLog.OperationEnum.update.name().equalsIgnoreCase(sqlCommandType)
                    || AuditLog.OperationEnum.delete.name().equalsIgnoreCase(sqlCommandType))
            {
                ISQLHandler sqlAuditHandler = null;
                try
                {
                    Executor executor = (Executor) invocation.getTarget();
                    Connection connection = executor.getTransaction().getConnection();
                    dbMetaDataHolder.init(connection);
                    Object parameter = null;
                    if (invocation.getArgs().length > 1)
                    {
                        parameter = invocation.getArgs()[1];
                    }
                    BoundSql boundSql = mappedStatement.getBoundSql(parameter);
                    Configuration configuration = mappedStatement.getConfiguration();
                    String sql = getParameterizedSql(configuration, boundSql);
                    if (AuditLog.OperationEnum.insert.name().equalsIgnoreCase(sqlCommandType))
                    {
                        sqlAuditHandler = new MySqlInsertSQLAuditHandler(connection, dbMetaDataHolder, clerkIdMethod, sql);
                    } else if (AuditLog.OperationEnum.update.name().equalsIgnoreCase(sqlCommandType))
                    {
                        sqlAuditHandler = new MySqlUpdateSQLAuditHandler(connection, dbMetaDataHolder, clerkIdMethod, sql);
                    } else if (AuditLog.OperationEnum.delete.name().equalsIgnoreCase(sqlCommandType))
                    {
                        sqlAuditHandler = new MySqlDeleteSqlAuditHandler(connection, dbMetaDataHolder, clerkIdMethod, sql);
                    }
                    if (sqlAuditHandler != null)
                    {
                        sqlAuditHandler.preHandle();
                    }
                } catch (Throwable ex)
                {
                    ex.printStackTrace();
                }
                Object result = invocation.proceed();
                try
                {
                    if (sqlAuditHandler != null)
                    {
                        sqlAuditHandler.postHandle();
                    }
                } catch (Throwable ex)
                {
                    ex.printStackTrace();
                }
                return result;
            }
        }
        return invocation.proceed();
    }

    private static String getParameterValue(Object obj)
    {
        String value;
        if (obj instanceof String)
        {
            value = "'" + obj.toString() + "'";
        } else if (obj instanceof Date)
        {
            DateFormat formatter = DateFormat.getDateTimeInstance(
                    DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.CHINA);
            value = "'" + formatter.format(new Date()) + "'";
        } else
        {
            if (obj != null)
            {
                value = obj.toString();
            } else
            {
                value = "null";
            }

        }
        return value;
    }

    private String getParameterizedSql(Configuration configuration, BoundSql boundSql)
    {
        Object parameterObject = boundSql.getParameterObject();
        List<ParameterMapping> parameterMappings = boundSql
                .getParameterMappings();
        String sql = pattern2.matcher(boundSql.getSql()).replaceAll(" ");
        if (CollectionUtils.isNotEmpty(parameterMappings) && parameterObject != null)
        {
            TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
            if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass()))
            {
                sql = pattern1.matcher(sql).replaceFirst(Matcher.quoteReplacement(getParameterValue(parameterObject)));
            } else
            {
                MetaObject metaObject = configuration.newMetaObject(parameterObject);
                for (ParameterMapping parameterMapping : parameterMappings)
                {
                    String propertyName = parameterMapping.getProperty();
                    if (metaObject.hasGetter(propertyName))
                    {
                        Object obj = metaObject.getValue(propertyName);
                        sql = pattern1.matcher(sql).replaceFirst(Matcher.quoteReplacement(getParameterValue(obj)));
                    } else if (boundSql.hasAdditionalParameter(propertyName))
                    {
                        Object obj = boundSql.getAdditionalParameter(propertyName);
                        sql = pattern1.matcher(sql).replaceFirst(Matcher.quoteReplacement(getParameterValue(obj)));
                    } else
                    {
                        sql = pattern1.matcher(sql).replaceFirst("缺失");
                    }
                }
            }
        }
        return sql;
    }

    @Override
    public Object plugin(Object target)
    {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties)
    {
        Boolean splitEnableOption = Boolean.valueOf(properties.getProperty("split", Boolean.FALSE.toString()));
        String defaultTableNameOption = String.valueOf(properties.getProperty("defaultTableName", "audit_log"));
        String preTableNameOption = String.valueOf(properties.getProperty("preTableName", "audit_log_"));
        String clerkIdMethodOption = String.valueOf(properties.getProperty("clerkIdMethod", ""));

        if (StringUtils.isNotBlank(clerkIdMethodOption))
        {
            try
            {
                String classAndMethodName[] = clerkIdMethodOption.split("#");
                if (classAndMethodName.length == 2)
                {
                    Class clerkClazz = Class.forName(classAndMethodName[0]);
                    clerkIdMethod = clerkClazz.getMethod(classAndMethodName[1]);
                }

            } catch (ClassNotFoundException | NoSuchMethodException e)
            {
            }
        }
        auditEnable = Boolean.valueOf(properties.getProperty("enable", Boolean.TRUE.toString()));
        dbMetaDataHolder = new DBMetaDataHolder(new AuditLogTableCreator(splitEnableOption, defaultTableNameOption, preTableNameOption));

    }

}
