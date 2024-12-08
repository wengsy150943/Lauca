package input;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

import abstraction.*;

/**
 * 测试数据库Schema信息的自动读取：自动从真实数据库中获取表模式
 * @author Ting Chen
 */

public class SchemaAutoReader {

//    private Logger logger = Logger.getLogger(SchemaReader.class);
    //记录各表之间的参照关系，保证table列表中的数据表序与数据表之间的参照序一致
    private Map<String, List<String> > referencedRelation = null;
    //获取数据库中的所有表名
    public List<String> getTableNameList(DatabaseMetaData dbmd,String databaseType,String username) throws SQLException {
        //访问当前用户下的所有表
        ResultSet tablesRS = null;
        if(databaseType.equals("oracle")) {
            tablesRS = dbmd.getTables("null", username.toUpperCase(), "%", new String[]{"TABLE"});
        }else
        {
            tablesRS = dbmd.getTables(null, "public", "%", new String[] { "TABLE" });
        }
        //得到所有表名
        List<String> tableNameList = new ArrayList<>();
        while (tablesRS.next()) {
            tableNameList.add(tablesRS.getString("TABLE_NAME"));
        }
        return tableNameList;
    }

    //获取每个表中的所有列名及其数据类型
    public List<Column> getColumnNameList(DatabaseMetaData dbmd, String databaseType,String username,String tableName) throws SQLException {
        ResultSet columnsRS = null;
        if(databaseType.equals("oracle")) {
            columnsRS = dbmd.getColumns(null,username.toUpperCase(),tableName,"%");
        }else {
            columnsRS = dbmd.getColumns(null,"%",tableName,"%");
        }
        String columnName = null;
        String dataType = null;
        List<Column> columns = new ArrayList<>();
        while (columnsRS.next()) {
            columnName = columnsRS.getString("COLUMN_NAME").toLowerCase();
            dataType = columnsRS.getString("TYPE_NAME").toLowerCase();
            dataType = dataTypeUnify(databaseType,dataType,columnsRS);
            columns.add(new Column(columnName, dataType2Int(dataType)));
        }
        return columns;
    }

    //获取每个表的主键
    public String[] getPrimaryKey(DatabaseMetaData dbmd,String databaseType,String username,String tableName) throws SQLException {
        ResultSet primaryKeyRS = null;
        List<String> primaryKeyList = new ArrayList<>();
        if(databaseType.equals("oracle")) {
            primaryKeyRS = dbmd.getPrimaryKeys(null,username.toUpperCase(),tableName);
        }else {
            primaryKeyRS = dbmd.getPrimaryKeys(null,null,tableName);
        }
        while(primaryKeyRS.next()){
            primaryKeyList.add(primaryKeyRS.getString("COLUMN_NAME").toLowerCase());
        }
        //list转string数组
        String[] pk = primaryKeyList.toArray(new String[primaryKeyList.size()]);
        return pk;
    }

    //获取每个表的外键及其依赖
    public List<ForeignKey> getForeignKey(DatabaseMetaData dbmd,String databaseType,String username,String tableName) throws SQLException {
        ResultSet foreignKeyRS = null;
        List<ForeignKey> foreignKeys = new ArrayList<>();
        List<String> pkTables = new ArrayList<>();
        referencedRelation.put(tableName,pkTables);
        if(databaseType.equals("oracle")) {
            foreignKeyRS = dbmd.getImportedKeys(null, username.toUpperCase(), tableName);
        }else {
            foreignKeyRS = dbmd.getImportedKeys(null, null, tableName);
        }
        List<String> localColumnsList = new ArrayList<>();
        String referencedTable = null;
        List<String> referencedColumnsList = new ArrayList<>();
        Map<String,List<String>> referTable2localCols = new HashMap<>();
        Map<String,List<String>> referTable2referCols = new HashMap<>();
        double averageReferenceScale = -1;
        while (foreignKeyRS.next()) {
            referencedTable = foreignKeyRS.getString("PKTABLE_NAME"); // 外键依赖的表
            if (referTable2localCols.containsKey(referencedTable)) {
                referTable2localCols.get(referencedTable).add(foreignKeyRS.getString("FKCOLUMN_NAME").toLowerCase());
                referTable2referCols.get(referencedTable).add(foreignKeyRS.getString("PKCOLUMN_NAME").toLowerCase());
            }
            else {
                referTable2localCols.put(referencedTable, new ArrayList<>());
                referTable2referCols.put(referencedTable, new ArrayList<>());
                referTable2localCols.get(referencedTable).add(foreignKeyRS.getString("FKCOLUMN_NAME").toLowerCase());
                referTable2referCols.get(referencedTable).add(foreignKeyRS.getString("PKCOLUMN_NAME").toLowerCase());
            }
            referencedRelation.get(tableName).add(referencedTable);
        }
        for(Map.Entry<String, List<String>> entry : referTable2localCols.entrySet()){
            referencedTable = entry.getKey();
            localColumnsList = entry.getValue();
            referencedColumnsList = referTable2referCols.get(referencedTable);
            //list转String数组
            String[] localColumns = localColumnsList.toArray(new String[localColumnsList.size()]);
            String[] referencedColumns = referencedColumnsList.toArray(new String[referencedColumnsList.size()]);
            foreignKeys.add(new ForeignKey(localColumns, referencedTable, referencedColumns,
                    averageReferenceScale));
        }

        return foreignKeys;
    }

    public List<Table> read(Connection conn,String databaseType,String username) throws SQLException{
        List<Table> tables = new ArrayList<>();
        Map<String,Table> tmp = new HashMap<>();
        referencedRelation = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        //连接真实数据库，从真实数据库中获取表模式：表名称，列名，数据类型，主键，外键
        DatabaseMetaData dbmd = conn.getMetaData();
        try{
            List<String> tableNameList = getTableNameList(dbmd,databaseType,username);
            for(String tableName : tableNameList){
                List<Column> columns = getColumnNameList(dbmd,databaseType,username,tableName);
                Partition partition = getPartition(conn, databaseType, tableName, columns);
                String[] primaryKey = getPrimaryKey(dbmd,databaseType,username,tableName);
                List<ForeignKey> foreignKeys = getForeignKey(dbmd,databaseType,username,tableName);

                Column[] tmp1 = new Column[columns.size()];
                columns.toArray(tmp1);
                ForeignKey[] tmp2 = new ForeignKey[foreignKeys.size()];
                foreignKeys.toArray(tmp2);
                int tableSize = -1;
                if (primaryKey == null) {
                    primaryKey = new String[0];
                }
                //对主键排序
                sortPrimaryKeys(primaryKey, tmp1);
                tmp.put(tableName,new Table(tableName, tableSize, tmp1, primaryKey, tmp2, partition));
                if(foreignKeys.size() == 0) {
                    queue.offer(tableName);
                    tables.add(new Table(tableName, tableSize, tmp1, primaryKey, tmp2, partition));
                }
            }
        } catch (SQLException e){
            e.printStackTrace();
        }
        //对表排序，如A依赖B，则B在A之前
        while(!queue.isEmpty()){
            String name = queue.poll();
            for(Map.Entry<String, List<String> > entry : referencedRelation.entrySet()){
                String tableName = entry.getKey();
                List<String> referencedTN = entry.getValue();
                if(referencedTN.size() == 0) continue;
                //使用迭代器的删除方法删除
                referencedTN.removeIf(name::equals);
                if(referencedTN.size() == 0){
                    tables.add(tmp.get(tableName));
                    queue.offer(tableName);
                }
            }
        }
//        System.out.println(tables);
//        logger.info("All input table information:\n" + tables);
        return tables;
    }

    // 从建表语句中得到分区信息，现在只支持ob，int型分区键，列名全小写
    private Partition getPartition(Connection conn, String databaseType, String tableName, List<Column> columns) throws SQLException {
        if (!databaseType.equals("mysql") && !databaseType.equals("tidb")){
            return null;
        }

        Statement statement = conn.createStatement();

        ResultSet rs = statement.executeQuery("show create table "+tableName+";");
        rs.next();
        String createSQL = rs.getString(2).toLowerCase();
        if (!createSQL.contains("partition by")){
            return null;
        }
        // 清理sql语句
        String[] partitionSQLs = createSQL.split("partition by")[1].split("\n");
        Pattern clearPattern = Pattern.compile("[,()]");
        for (int i = 0;i < partitionSQLs.length ;++i ) {
            partitionSQLs[i] = clearPattern.matcher(partitionSQLs[i]).replaceAll(" ");
        }

        List<String> partitionNameList = new ArrayList<>();
        PartitionFunction  partitionRule;
        List<List<Integer>> partitionParam = new ArrayList<>();

        String[] tmp = partitionSQLs[0].split("\\s+");
        String partitionKey = tmp[tmp.length - 1];
        if (databaseType.equals("tidb")){
            partitionKey = tmp[tmp.length - 3];
            partitionKey = partitionKey.substring(1,partitionKey.length() -1);
        }

        // 检查是否真的有这个列，并且是否是int类型
        for (Column c : columns) {
            if (c.getName().toLowerCase().equals(partitionKey)){
                if (c.getDataType() >= 3){
                    System.err.println("not support data type!");
                    return null;
                }
            }
        }


        // 只支持hash list range 三种
        switch (partitionSQLs[0].trim().split(" ")[0]){
            case "hash":
                partitionRule = PartitionFunction.HASH;
                break;
            case "range":
                partitionRule = PartitionFunction.RANGE;
                break;
            case "list":
                partitionRule = PartitionFunction.LIST;
                break;
            default:
                return null;
        }

        if (databaseType.equals("tidb") && partitionRule == PartitionFunction.HASH){
            int partitionCnt = Integer.parseInt(tmp[tmp.length - 1]);
            for (int i = 0; i < partitionCnt; i++) {
                partitionNameList.add("p"+i);
                ArrayList<Integer> params = new ArrayList<>();
                params.add(i);
                partitionParam.add(params);
            }
        }

        int lowerBound = Integer.MIN_VALUE;
        for (int i = 1; i < partitionSQLs.length; i++) {
            String[] token = partitionSQLs[i].trim().split(" ");
            partitionNameList.add(token[1]);
            List<Integer> params = new ArrayList<>();

            switch (partitionRule){
                case HASH:
                    /*
                    -- ob
                     partition by hash(c_w_id)
                     (partition p0,
                     partition p1,
                     partition p2)

                     -- tidb
                     partition by hash (`c_w_id`) partition 5
                     */
                    params.add(i-1);
                    break;
                case RANGE:
                    /*
                    partition by range(c1) (
                        partition p0 values less than(100),
                        partition p1 values less than(500),
                        partitions p2 values less than(maxvalue)
                    );
                     */
                    params.add(lowerBound);
                    int upperBound = Integer.MAX_VALUE;
                    if (!token[token.length - 1].matches(".*[a-z].*")){ // 如果不是最后一个
                        upperBound = Integer.parseInt(token[token.length - 1]);
                    }
                    params.add(upperBound);
                    lowerBound = upperBound;
                    break;
                case LIST:
                    /*
                    partition by list(c1) (
                        partition p0 values in (1,2,3),
                        partition p1 values in (5, 6)，
                        partition p2 values in (default)
                    );
                     */
                    for (int j = 4; j < token.length; j++) {
                        if (!token[token.length - 1].matches(".*[a-z].*")){ // 如果是最后一个
                            params.add( Integer.parseInt(token[token.length - 1]) );
                        }
                        else {
                            params.add(null);
                        }
                    }
                    break;
                default:
                    break;
            }
            partitionParam.add(params);
        }


        return new Partition<>(partitionNameList, partitionRule, partitionParam, partitionKey);
    }

    //统一不同数据库的数据类型
    private  String dataTypeUnify(String databaseType,String dataType,ResultSet columnsRS) throws SQLException{
        switch (databaseType) {
            case "mysql":
            case "tidb":
            case "postgresql":
            case "kingbase":
                if(dataType.contains("int"))
                    dataType = "integer";
                else if(dataType.contains("float")||dataType.contains("double"))
                    dataType = "real";
                else if(dataType.equals("timestamp")||dataType.equals("datetime"))
                    dataType = "datetime";
                else if(dataType.contains("char"))
                    dataType = "varchar";
                else if(dataType.equals("bool"))
                    dataType = "boolean";
                else if(dataType.equals("numeric"))
                    dataType = "decimal";
                break;
            case "oracle":
                if(dataType.equals("number")){
                    int digits = columnsRS.getInt("DECIMAL_DIGITS");
                    if(digits >= 0)
                        dataType = "decimal";
                    else
                        dataType = "integer";
                }
                else if(dataType.equals("float"))
                    dataType = "real";
                else if(dataType.equals("date"))
                    dataType = "datetime";
                else if(dataType.contains("char"))
                    dataType = "varchar";
                break;
            default :
                break;
        }
        return dataType;
    }
    //数据类型转换
    private int dataType2Int(String dataType) {

        switch (dataType) {
            case "integer":
                return 0;
            case "real":
                return 1;
            case "decimal":
                return 2;
            case "datetime":
                return 3;
            case "varchar":
                return 4;
            case "boolean":
                return 5;
            default:
                return -1;
        }
    }

    //按照主键在所有列中的次序排序
    private void sortPrimaryKeys (String[] primaryKey, Column[] columns){
        Map<Integer, String> pks = new HashMap<>();
        for (String s : primaryKey) {
            for (int j = 0; j < columns.length; j++) {
                if (s.equals(columns[j].getName()))
                    pks.put(j, s);
            }
        }
        int i = 0;
        for(String pk : pks.values()){
            primaryKey[i] = pk;
            i ++;
        }
    }

}
