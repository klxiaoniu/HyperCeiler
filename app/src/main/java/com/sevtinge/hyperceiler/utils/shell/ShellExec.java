package com.sevtinge.hyperceiler.utils.shell;

import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.callback.ITAG;
import com.sevtinge.hyperceiler.utils.log.AndroidLogUtils;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可以执行多条 Shell 命令并实时获取结果的 Shell 工具。
 * 本工具使用简单的方法延续 Su/Sh 命令执行窗口，使得调用者无须频繁的执行 Su。
 * 调用示例:
 * <pre> {@code
 * Example 1:
 * new ShellExec("ls", true, true, new ShellUtils.ICommandOutPut() {
 *             @Override
 *             public void readOutput(String out, boolean finish) {
 *                 AndroidLogUtils.LogI(ITAG.TAG, "out: " + out + " finish: " + finish);
 *             }
 *
 *             @Override
 *             public void readError(String out) {
 *                 AndroidLogUtils.LogI(ITAG.TAG, "error: " + out);
 *             }
 *
 *             @Override
 *             public void result(String command, int result) {
 *                 AndroidLogUtils.LogI(ITAG.TAG, "command: " + command + " result: " + result);
 *             }
 *         })
 *             .sync()
 *             .run("echo done")
 *             .sync()
 *             .run("touch /data/adb/2")
 *             .sync()
 *             .close();
 *
 * ------------------------------------------------
 *
 * Example 2:
 * ShellExec shell = new ShellExec(true, true, null);
 *
 * public void test(){
 *     shell.run("ls").sync();
 *     AndroidLogUtils.LogI(ITAG.TAG, "result: " + shell.isResult());
 *     AndroidLogUtils.LogI(ITAG.TAG, "out: " + shell.getOutPut.toString() + " error: " + shell.getError.toString());
 *     shell.close();
 * }
 * }
 * 请在适当的时机调用 {@link ShellExec#close} 用来释放资源。
 * @author 焕晨HChen
 * @noinspection UnusedReturnValue
 */
public class ShellExec {
    private final Process process;
    private final DataOutputStream os;
    private static IPassCommands pass0;
    private static IPassCommands pass1;
    private final ArrayList<String> outPut = new ArrayList<>();
    private final ArrayList<String> error = new ArrayList<>();
    private final ArrayList<String> cList = new ArrayList<>();

    private final boolean result;
    private final boolean init;
    private boolean destroy;
    private boolean appending = false;
    protected int setResult = -1;
    private int count = 1;

    protected static void setICommand(IPassCommands pass, int mode) {
        switch (mode) {
            case 0 -> pass0 = pass;
            case 1 -> pass1 = pass;
        }
    }

    /**
     * 参考 {@link ShellExec#ShellExec(String, boolean, boolean, ICommandOutPut)}
     */
    public ShellExec(boolean root) {
        this("", root, false, null);
    }

    /**
     * 参考 {@link ShellExec#ShellExec(String, boolean, boolean, ICommandOutPut)}
     */
    public ShellExec(boolean root, @Nullable ICommandOutPut listen) {
        this("", root, false, listen);
    }

    /**
     * 参考 {@link ShellExec#ShellExec(String, boolean, boolean, ICommandOutPut)}
     */
    public ShellExec(boolean root, boolean result) {
        this("", root, result, null);
    }

    /**
     * 参考 {@link ShellExec#ShellExec(String, boolean, boolean, ICommandOutPut)}
     */
    public ShellExec(boolean root, boolean result, @Nullable ICommandOutPut listen) {
        this("", root, result, listen);
    }

    /**
     * 构造函数，完成初始化等任务。
     *
     * @param command 需要执行的第一个命令，可以留空。
     * @param root    是否使用 Root 身份执行。
     * @param result  是否需要获取每条命令的返回值。
     * @param listen  回调方法，可以是 null，类有能力处理。
     */
    public ShellExec(String command, boolean root, boolean result, @Nullable ICommandOutPut listen) {
        try {
            OutPut.setOutputListen(listen);
            Error.setOutputListen(listen);
            this.result = result;
            boolean run = command != null && !("".equals(command));
            process = Runtime.getRuntime().exec(root ? "su" : "sh");
            // 注意处理
            if (root) {
                // boolean r = process.waitFor(600, TimeUnit.MILLISECONDS);
                // if (r) {
                //     process.destroy();
                //     throw new RuntimeException("Root permission not obtained!");
                // }
                Check check = new Check(process, this);
                check.start();
            }
            os = new DataOutputStream(process.getOutputStream());
            if (run) {
                write(command);
            }
            if (result) {
                Error error = new Error(process.getErrorStream(), this, listen != null);
                OutPut output = new OutPut(process.getInputStream(), this, listen != null);
                clear();
                pass(command);
                error.start();
                output.start();
                if (run) done(0);
            }
            init = true;
            destroy = false;
        } catch (IOException e) {
            throw new RuntimeException("ShellExec boot failed!! E: " + e);
            // AndroidLogUtils.LogE(ITAG.TAG, "ShellExec E", e);
            // init = false;
        }
    }

    /**
     * 需要执行的命令，一次性可执行完毕的。
     *
     * @param command 命令
     * @return this
     */
    public synchronized ShellExec run(String command) {
        if (!init) return this;
        if (destroy) throw new RuntimeException("This shell has been destroyed!");
        if (appending) {
            throw new RuntimeException("Shell is in append mode!");
        }
        try {
            if (result) {
                clear();
                pass(command);
            }
            write(command);
            if (result) {
                done(count);
                count = count + 1;
            }
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "ShellExec run E", e);
        }
        return this;
    }

    /**
     * 进入追加模式，在这个模式你可以逐行输入命令，并请在结束时显性调用 over() 方法。
     *
     * @param command 追加命令
     * @return this
     */
    public synchronized ShellExec add(String command) {
        if (!init) return this;
        if (destroy) throw new RuntimeException("This shell has been destroyed!");
        appending = true;
        clear();
        try {
            write(command);
            if (result) {
                cList.add(command);
                count = count + 1;
            }
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "ShellExec append E", e);
        }
        return this;
    }

    /**
     * 结束追加模式。
     *
     * @return this
     */
    public synchronized ShellExec over() {
        if (!init) return this;
        if (destroy) throw new RuntimeException("This shell has been destroyed!");
        appending = false;
        if (result) {
            pass(cList.toString());
            done(count);
        }
        return this;
    }

    /**
     * 同步命令。
     * 进程将会在该条命令完全执行完毕并输出结束内容前等待。
     * 如果你需要获取命令的返回值，输出内容，请务必使用！
     *
     * @return this
     */
    public synchronized ShellExec sync() {
        if (!init) return this;
        if (destroy) throw new RuntimeException("This shell has been destroyed!");
        if (appending) {
            throw new RuntimeException("Shell is in append mode!");
        }
        try {
            this.wait();
        } catch (InterruptedException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "ShellExec sync E", e);
        }
        return this;
    }

    /**
     * 返回 Shell 工具是否初始化完成。
     *
     * @return 是否初始化完成。
     */
    public boolean ready() {
        return init;
    }

    /**
     * 获取本 Shell 是否已经销毁。
     *
     * @return Shell 状态
     */
    public boolean isDestroy() {
        return destroy;
    }

    /**
     * 使进程崩溃，正常情况不要手动调用。
     */
    protected void error() {
        throw new RuntimeException("Shell process exited abnormally, possibly due to lack of Root permission!!");
    }

    private void clear() {
        setResult = -1;
        outPut.clear();
        error.clear();
    }

    private void pass(String command) {
        if (pass0 != null)
            pass0.passCommands(command);
        if (pass1 != null)
            pass1.passCommands(command);
    }

    private synchronized void write(String command) throws IOException {
        os.write(command.getBytes());
        os.writeBytes("\n");
        os.flush();
    }

    /**
     * 返回当前命令的执行结果，建议搭配 sync，否则可能错位。
     *
     * @return 执行结果
     */
    public boolean isResult() {
        return setResult == 0;
    }

    /**
     * 返回当前命令的执行返回值，建议搭配 sync，否则可能错位。
     *
     * @return 执行返回值
     */
    public int getResult() {
        return setResult;
    }

    /**
     * 获取输出数据的 List 集合，强烈建议和 sync() 一同使用！
     *
     * @return 输出数据的集合
     */
    public ArrayList<String> getOutPut() {
        return outPut;
    }

    /**
     * 获取输出错误数据的 List 集合，强烈建议和 sync() 一同使用！
     *
     * @return 输出错误数据的集合
     */
    public ArrayList<String> getError() {
        return error;
    }

    private void done(int count) {
        try {
            os.writeBytes("result=$?; string=\"The execution of command <" + count + "> is complete. Return value: <$result>\"; " +
                "if [[ $result != 0 ]]; then echo $string 1>&2; else echo $string 2>/dev/null; fi");
            // os.writeBytes("echo \"The execution of command <" + count + "> is complete. Return value: <$?>\" 1>&2 2>&1");
            os.writeBytes("\n");
            os.flush();
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "ShellExec done E", e);
        }
    }

    /**
     * 关闭 ShellWindow。
     *
     * @return 本 process 的最终执行结果
     */
    public int close() {
        if (!init) return -1;
        if (destroy) throw new RuntimeException("This shell has been destroyed!");
        int result = -1;
        try {
            clear();
            write("exit");
            result = process.waitFor();
            process.destroy();
            os.close();
        } catch (IOException e) {
            AndroidLogUtils.LogE(ITAG.TAG, "ShellExec close E", e);
        } catch (InterruptedException f) {
            AndroidLogUtils.LogE(ITAG.TAG, "ShellExec getResult E", f);
        }
        destroy = true;
        return result;
    }

    private void log(String log) {
        AndroidLogUtils.LogI(ITAG.TAG, log);
    }

    protected interface IPassCommands {
        void passCommands(String command);
    }

    private static class Check extends Thread {
        final Process process;
        final ShellExec shellExec;

        public Check(Process process, ShellExec shellExec) {
            this.process = process;
            this.shellExec = shellExec;
        }

        @Override
        public void run() {
            boolean result = false;
            try {
                result = process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                AndroidLogUtils.LogE(ITAG.TAG, "Shell Check run E", e);
            }
            if (result) {
                try {
                    shellExec.notify();
                } catch (IllegalMonitorStateException e) {
                }
                shellExec.error();
            }
        }
    }

    private static class OutPut extends Thread {
        private final InputStream mInput;
        private final Pattern pattern;
        private final Command command;
        private final String contrast;
        private final ShellExec shellExec;

        private static ICommandOutPut mICommandOutPut;

        public OutPut(InputStream inputStream, ShellExec shellExec, boolean listen) {
            contrast = "The execution of command <";
            pattern = Pattern.compile(".*<(\\d+)>.*<(\\d+)>.*");
            if (listen) command = new Command(0);
            else command = null;
            this.shellExec = shellExec;
            mInput = inputStream;
        }

        public static void setOutputListen(ICommandOutPut iCommandOutPut) {
            mICommandOutPut = iCommandOutPut;
        }

        @Override
        public void run() {
            boolean use = mICommandOutPut != null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(mInput))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // AndroidLogUtils.LogI(ITAG.TAG, "out: " + line);
                    if (line.contains(contrast)) {
                        Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            String count = matcher.group(1);
                            String result = matcher.group(2);
                            if (result != null && count != null) {
                                if (use) {
                                    mICommandOutPut.result(command.passCommands.get(Integer.parseInt(count)),
                                        Integer.parseInt(result));
                                    mICommandOutPut.readOutput("Finish!!", true);
                                }
                                shellExec.setResult = Integer.parseInt(result);
                                synchronized (shellExec) {
                                    try {
                                        shellExec.notify();
                                    } catch (IllegalMonitorStateException e) {
                                    }
                                }
                                continue;
                            }
                        }
                    }
                    shellExec.outPut.add(line);
                    if (use) mICommandOutPut.readOutput(line, false);
                }
            } catch (IOException e) {
                AndroidLogUtils.LogE(ITAG.TAG, "Shell OutPut run E", e);
            }
        }


        private void log(String log) {
            AndroidLogUtils.LogI(ITAG.TAG, log);
        }
    }

    private static class Error extends Thread {
        private final InputStream mInput;
        private final ShellExec shellExec;
        private final Pattern pattern;
        private final Command command;
        private static ICommandOutPut mICommandOutPut;

        public Error(InputStream inputStream, ShellExec shellExec, boolean listen) {
            pattern = Pattern.compile(".*<(\\d+)>.*<(\\d+)>.*");
            if (listen) command = new Command(1);
            else command = null;
            mInput = inputStream;
            this.shellExec = shellExec;
        }

        public static void setOutputListen(ICommandOutPut iCommandOutPut) {
            mICommandOutPut = iCommandOutPut;
        }

        @Override
        public void run() {
            boolean use = mICommandOutPut != null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(mInput))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // AndroidLogUtils.LogI(ITAG.TAG, "error: " + line);
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        String count = matcher.group(1);
                        String result = matcher.group(2);
                        if (result != null && count != null) {
                            if (use)
                                mICommandOutPut.result(command.passCommands.get(Integer.parseInt(count)),
                                    Integer.parseInt(result));
                            shellExec.setResult = Integer.parseInt(result);
                            synchronized (shellExec) {
                                try {
                                    shellExec.notify();
                                } catch (IllegalMonitorStateException e) {
                                }
                            }
                            continue;
                        }
                    }
                    shellExec.error.add(line);
                    if (use) mICommandOutPut.readError(line);
                }
            } catch (IOException e) {
                AndroidLogUtils.LogE(ITAG.TAG, "Shell Error run E", e);
            } catch (NumberFormatException f) {
                AndroidLogUtils.LogE(ITAG.TAG, "Shell get result E", f);
            }
        }

        private void log(String log) {
            AndroidLogUtils.LogI(ITAG.TAG, log);
        }
    }

    protected static class Command implements IPassCommands {
        public ArrayList<String> passCommands = new ArrayList<>();

        public Command(int mode) {
            setICommand(this, mode);
        }

        @Override
        public void passCommands(String command) {
            passCommands.add(command);
        }
    }


    public interface ICommandOutPut {
        /**
         * 重写本方法可以实时获取常规流数据。
         *
         * @param out 常规流数据
         */
        default void readOutput(String out, boolean finish) {
        }

        /**
         * 重写本方法可以实时获取错误流数据。
         *
         * @param out 错误流数据
         */
        default void readError(String out) {
        }

        /**
         * 重写本方法可以实时获取每条命令的执行结果。
         *
         * @param command 命令
         * @param result  结果
         */
        default void result(String command, int result) {
        }
    }
}
