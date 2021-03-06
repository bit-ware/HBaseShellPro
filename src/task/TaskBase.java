package task;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import main.HBaseShellPro;

import org.apache.hadoop.hbase.client.HTable;

import exception.HBSException;
import exception.HBSExceptionRowLimitReached;

import tnode.TNodeBase;
import tnode.TNodeDatabase;
import tnode.TNodeRow;
import tnode.TNodeTable;
import utils.ResultLog;
import utils.Utils;

public abstract class TaskBase implements Task {
    public enum TaskType {
        CLEAR,
        CONNECT,
        COUNT,
        CREATE,
        DELETE,
        DESCRIBE,
        FILTER,
        GET,
        HELP,
        HISTORY,
        LIST,
        MULTILINE,
        PUT,
        QUIT,
        RENAME,
        READONLY,
        REG_DELETE,
        SCAN,
        VERSION,
    }

    public enum Level {
        TABLE,
        ROW,
        FAMILY,
        QUALIFIER,
        VALUE,
        OTHER,
    }

    private static final String CLASS_NAME_PREFIX = "task.Task_";

    protected static final ResultLog log = ResultLog.getLog();

    public Map<Level, Object> levelParam = new HashMap<Level, Object>();
    public Level              level      = null;

    private boolean notifyEnabled = false;
    private boolean needConfirm   = false;

    private static Map<String, TaskType> aliasMap = null;
    private static boolean               forced   = false;
    private static boolean               quiet    = false;
    private static long                  rowLimit = Long.MAX_VALUE;

    private TaskType taskType = null;

    @Override
    public final void printHelp() {
        log.info(getTaskType() + " - " + description());
        log.info("");
        log.info("  usage   : " + usage());
        log.info("  example : " + example());
        log.info("  alias   : " + alias());
        log.info("");
    }

    protected abstract String description();
    protected abstract String usage();

    @Override
    public List< ? > alias() {
        String aliasName = "alias_" + getTaskName();

        try {
            Field field = HBaseShellPro.class.getField(aliasName);
            return (List< ? >)field.get(null);
        } catch (Exception e) {     // all exceptions
            log.warn(null, e);
        }

        return null;
    }

    @Override
    public final void doTask(String[] args)
    throws IOException {
        changeLogOnStart();

        if (HBaseShellPro.readonly && !isReadOnly()) {
            throw new IOException("Non-readonly tasks not allowed in readonly mode\nEnter 'help readonly<RETURN>' for more information");
        }

        parseArgs(args);

        if (!doConfirm()) {
            return;
        }

        resetAllCount();

        try {
            log.setQuiet(quiet);
            execute();
            log.setQuiet(false);
        } catch (HBSExceptionRowLimitReached e) {
            // OK
        } catch (HBSException e) {
            log.error(null, e);
        }
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    private boolean doConfirm()
    throws IOException {
        if (forced || !needConfirm) {
            return true;
        }

        boolean notifyEnabled = this.notifyEnabled;
        this.notifyEnabled = false;

        try {
            confirm();
        } catch (HBSExceptionRowLimitReached e) {
            // OK
        } catch (HBSException e) {
            log.error(null, e);
        }

        this.notifyEnabled = notifyEnabled;

        System.out.print("******************************\n");
        boolean confirmed = HBaseShellPro.confirmFor("Sure to " + getTaskType() + "?");
        System.out.print("******************************\n");

        return confirmed;
    }

    public void resetAllCount() {
        HBaseShellPro.resetAllCount();
    }

    protected void changeLogOnStart() {
        log.startNew();
    }

    protected final void parseArgs(String[] args)
    throws IOException {
        if (!checkArgNumber(args.length)) {
            throw new IOException("Invalid argument number '" + args.length + "'");
        }

        // levelParam
        assignParam(args);

        // level
        this.level = getLevel();

        // needConfirm
        this.needConfirm = needConfirm();

        // notifyEnabled
        this.notifyEnabled = notifyEnabled();

        // output
        outputParam();
    }

    protected abstract boolean checkArgNumber(int argNumber);

    protected void assignParam(String[] args)
    throws IOException {
        try {
            levelParam.put(Level.TABLE,     Pattern.compile(args[0]));
            levelParam.put(Level.ROW,       Pattern.compile(args[1]));
            levelParam.put(Level.FAMILY,    Pattern.compile(args[2]));
            levelParam.put(Level.QUALIFIER, Pattern.compile(args[3]));
            levelParam.put(Level.VALUE,     Pattern.compile(args[4]));
        } catch (ArrayIndexOutOfBoundsException e) {
            // OK
        }
    }

    protected Level getLevel() {
        return null;
    }

    protected boolean needConfirm() {
        return false;
    }

    protected boolean notifyEnabled() {
        return false;
    }

    protected boolean isToOutput() {
        return true;
    }

    private void outputParam() {
        log.info("taskType        : " + getTaskType());

        if (level != null) {
            log.info("level           : " + level);
        }

        if (levelParam.get(Level.TABLE) != null) {
            log.info("param-Table     : " + levelParam.get(Level.TABLE));
        }

        if (levelParam.get(Level.ROW) != null) {
            log.info("param-RowKey    : " + levelParam.get(Level.ROW));
        }

        if (levelParam.get(Level.FAMILY) != null) {
            log.info("param-Family    : " + levelParam.get(Level.FAMILY));
        }

        if (levelParam.get(Level.QUALIFIER) != null) {
            log.info("param-Qualifier : " + levelParam.get(Level.QUALIFIER));
        }

        if (levelParam.get(Level.VALUE) != null) {
            log.info("param-Value     : " + levelParam.get(Level.VALUE));
        }

        if (levelParam.get(Level.OTHER) != null) {
            log.info("param-Other     : " + levelParam.get(Level.OTHER));
        }

        log.info("---------------------------------------");
    }

    public void confirm()
    throws IOException, HBSException {
        execute();
    }

    public void execute()
    throws IOException, HBSException {
        new TNodeDatabase(this, isToOutput()).handle();
    }

    //
    // utils
    //

    private static String getTaskClassName(TaskType taskType)
    throws ClassNotFoundException {
        return CLASS_NAME_PREFIX + taskType.toString().toLowerCase();
    }

    private TaskType getTaskType() {
        if (taskType != null) {
            return taskType;
        }

        this.taskType = getAliasMap().get(getTaskName());
        return taskType;
    }

    private String getTaskName() {
        String className = getClass().getName();
        return className.substring(CLASS_NAME_PREFIX.length());
    }

    public static final TaskType getTaskType(String string)
    throws IOException {
        String   command  = parseCommand(string);
        TaskType taskType = getAliasMap().get(command);

        if (taskType == null) {
            throw new IOException("Undefined command '" + command.toUpperCase() + "'");
        }

        return taskType;
    }

    private static String parseCommand(String string) {
        // check if forced
        forced = string.endsWith("!");

        if (forced) {
            string = string.substring(0, string.length() - 1);
        }

        // check if quiet
        quiet = string.endsWith("-");

        if (quiet) {
            string = string.substring(0, string.length() - 1);
        }

        // get row limit parameter
        List<String> groups = Utils.match(string, "(\\d+)$");

        if (groups.size() == 2) {
            String g1 = groups.get(1);
            rowLimit = Long.valueOf(g1);
            string   = string.substring(0, string.length() - g1.length());
        } else {
            rowLimit = Long.MAX_VALUE;
        }

        return string;
    }

    public static final Task getTask(TaskType taskType) {
        try {
            String     taskClassName = getTaskClassName(taskType);
            Class< ? > clazz         = Class.forName(taskClassName);

            Class< ? > [] parameterTypes = new Class[] {};
            Constructor< ? > constructor = clazz.getConstructor(parameterTypes);

            return (Task) constructor.newInstance(new Object[] {});
        } catch (Exception e) {         // all exceptions
            // a lot of exceptions, but there should be no errors if all taskType implemented
            log.error(null, e);
            return null;
        }
    }

    public static Map<String, TaskType> getAliasMap() {
        if (aliasMap != null) {
            return aliasMap;
        }

        aliasMap = new HashMap<String, TaskType>();

        for (TaskType taskType : TaskType.values()) {
            Task      task    = TaskBase.getTask(taskType);
            List< ? > aliases = task.alias();

            for (Object alias : aliases) {
                aliasMap.put((String) alias, taskType);
            }

            aliasMap.put(taskType.toString().toLowerCase(), taskType);
        }

        return aliasMap;
    }

    //
    // notify
    //

    public void notifyFound(TNodeBase node)
    throws IOException, HBSException {
        if (!notifyEnabled) {
            return;
        }

        HTable table = null;

        switch (node.level) {
        case TABLE :
            table = ((TNodeTable)node).getTable();

            try {
                foundTable(table);
            } finally {
                ((TNodeTable)node).closeTable(table);
            }

            break;

        case ROW :
            table = ((TNodeRow)node).table;
            foundRow(table, node.name);
            break;

        case FAMILY :
            table = ((TNodeRow)node.parent).table;
            foundFamily(table, node.parent.name, node.name);
            break;

        case QUALIFIER :
            table = ((TNodeRow)node.parent.parent).table;
            foundQualifier(table, node.parent.parent.name, node.parent.name, node.name);
            break;

        case VALUE:
            table = ((TNodeRow)node.parent.parent.parent).table;
            foundValue(table, node.parent.parent.parent.name, node.parent.parent.name, node.parent.name, node.name);
            break;

        default:
            break;
        }
    }

    protected void foundTable(HTable table)
    throws IOException, HBSException {
        // Do nothing
    }

    protected void foundRow(HTable table, String row)
    throws IOException {
        // Do nothing
    }

    protected void foundFamily(HTable table, String row, String family)
    throws IOException {
        // Do nothing
    }

    protected void foundQualifier(HTable table, String row, String family, String qualifier)
    throws IOException {
        // Do nothing
    }

    protected void foundValue(HTable table, String row, String family, String qualifier, String value)
    throws IOException {
        // Do nothing
    }

    public boolean isMatch(Level level, String target) {
        Pattern pattern = (Pattern)levelParam.get(level);

        if (pattern == null) {
            return true;
        }

        Matcher matcher = pattern.matcher(target);
        return matcher.find();
    }

    public boolean isGet() {
        return getTaskType() == TaskType.GET;
    }

    public boolean isFilter() {
        return getTaskType() == TaskType.FILTER;
    }

    public static long getRowLimit() {
        return rowLimit;
    }
}
