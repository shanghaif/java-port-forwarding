package cn.org.hentai.server.protocol.commander;

import cn.org.hentai.server.dao.HostDAO;
import cn.org.hentai.server.model.Host;
import cn.org.hentai.server.model.Port;
import cn.org.hentai.server.protocol.Packet;
import cn.org.hentai.server.protocol.SocketSession;
import cn.org.hentai.server.protocol.command.Command;
import cn.org.hentai.server.protocol.command.StartForwardCommand;
import cn.org.hentai.server.util.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedList;

/**
 * Created by Expect on 2018/1/25.
 */
public class CommandSession extends SocketSession
{
    HostDAO hostDAO;
    Host host = null;
    Socket connection = null;
    long lastActiveTime = System.currentTimeMillis();
    LinkedList<Command> commands = new LinkedList<Command>();                     // 待下发的指令

    public CommandSession(Socket connection)
    {
        this.connection = connection;
        hostDAO = BeanUtils.create(HostDAO.class);
    }

    @Override
    protected void converse() throws Exception
    {
        InputStream inputStream = this.connection.getInputStream();
        OutputStream outputStream = this.connection.getOutputStream();

        // 先读取一个包，确定一下主机端的身份
        host = authenticate(inputStream, outputStream);
        HostConnectionManager.getInstance().register(host.getId(), this);
        Log.debug("Host: " + host.getName() + " connected...");

        while (true)
        {
            // 测试连接的可用性
            testConnection(inputStream, outputStream);

            // 是否有需要下发的指令？
            sendCommand(inputStream, outputStream);

            Thread.sleep(10);
        }
    }

    /**
     * 下发指令，一次只下发一个指令
     * @param inputStream
     * @param outputStream
     */
    private void sendCommand(InputStream inputStream, OutputStream outputStream) throws Exception
    {
        if (commands.size() == 0) return;
        Command cmd = commands.removeFirst();
        if (null == cmd) return;
        byte[] packet = Packet.create(host.getId(), Packet.ENCRYPT_TYPE_DES, cmd.getCode(), cmd.getBytes(), host.getAccesstoken());
        outputStream.write(packet);
        Packet.read(inputStream);
        lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 下发一个无意义的数据包，进行连接测试
     * @param inputStream
     * @param outputStream
     */
    private void testConnection(InputStream inputStream, OutputStream outputStream) throws Exception
    {
        int timeout = Configs.getInt("server.test-packet.timeout", 1000 * 30);
        if (System.currentTimeMillis() - lastActiveTime < timeout) return;
        byte[] data = NonceStr.generate(32).getBytes();
        byte[] packet = Packet.create(host.getId(), Packet.ENCRYPT_TYPE_DES, Command.CODE_TEST, data, host.getAccesstoken());
        outputStream.write(packet);
        Packet.read(inputStream, true);
        lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 读取一个数据包，进行身份验证
     * @param inputStream
     * @param outputStream
     * @return Host
     * @throws Exception
     */
    private Host authenticate(InputStream inputStream, OutputStream outputStream) throws Exception
    {
        byte[] packet = Packet.read(inputStream, true);
        int hostId = Packet.getHostId(packet);
        Host host = hostDAO.getById(hostId);
        if (null == host) throw new RuntimeException("no such host: " + hostId);
        byte[] decrypted = Packet.getData(packet, host.getAccesstoken());
        if (!"authenticate".equals(new String(decrypted))) throw new RuntimeException("invalid authenticate packet: " + ByteUtils.toString(packet));
        outputStream.write(Packet.create(hostId, Packet.ENCRYPT_TYPE_DES, Command.CODE_AUTHENTICATE, NonceStr.generate(32).getBytes(), host.getAccesstoken()));
        return host;
    }

    @Override
    protected void release()
    {
        try { this.connection.close(); } catch(Exception e) { }
        try { HostConnectionManager.getInstance().unregister(host.getId()); } catch(Exception e) { }
    }

    public synchronized void requestForward(int seqId, Port port)
    {
        commands.add(new StartForwardCommand(seqId, port.getHostPort()));
    }
}