package cn.org.hentai.server.proxy;

import cn.org.hentai.server.dao.HostDAO;
import cn.org.hentai.server.model.Host;
import cn.org.hentai.server.util.Log;
import cn.org.hentai.server.util.NonceStr;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Created by Expect on 2018/1/25.
 */
public class CommandSession extends SocketSession
{
    @Autowired
    HostDAO hostDAO;

    Host host = null;
    Socket client = null;
    long lastActiveTime = System.currentTimeMillis();

    public CommandSession(Socket client)
    {
        this.client = client;
    }

    @Override
    protected void converse() throws Exception
    {
        InputStream inputStream = this.client.getInputStream();
        OutputStream outputStream = this.client.getOutputStream();
        // 先读取一个包，确定一下主机端的身份
        host = authenticate(inputStream);
        CommandServer.register(host);

        while (true)
        {
            // 测试连接的可用性
            testConnection(inputStream, outputStream);
            // 是否有需要下发的指令？
            // 是否有上发的数据包？
        }
    }

    /**
     * 下发一个无意义的数据包，进行连接测试
     * @param inputStream
     * @param outputStream
     */
    private void testConnection(InputStream inputStream, OutputStream outputStream) throws Exception
    {
        if (System.currentTimeMillis() - lastActiveTime < 1000 * 10) return;
        byte[] data = NonceStr.generate(32).getBytes();
        byte[] packet = Packet.create(host.getId(), Constants.ENCRYPT_TYPE_DES, Constants.COMMAND_TEST_CONNECTION, data, host.getAccesstoken());
        outputStream.write(packet);
        Packet.read(inputStream);
        lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 读取一个数据包，进行身份验证
     * @param inputStream
     * @throws Exception
     */
    private Host authenticate(InputStream inputStream) throws Exception
    {
        byte[] packet = Packet.read(inputStream);
        int hostId = Packet.getHostId(packet);
        Host host = hostDAO.getById(hostId);
        if (null == host) throw new RuntimeException("no such host: " + hostId);
        byte[] data = Packet.getData(packet, host.getAccesstoken());
        return host;
    }

    @Override
    protected void release()
    {
        try { this.client.close(); } catch(Exception e) { }
        try { CommandServer.release(host); } catch(Exception e) { }
    }
}
