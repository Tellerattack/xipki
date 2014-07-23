/*
 * Copyright (c) 2014 Lijun Liao
 *
 * TO-BE-DEFINE
 *
 */

package org.xipki.remotep11.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.xipki.security.api.SignerException;
import org.xipki.security.common.ParamChecker;

/**
 * @author Lijun Liao
 */

class DefaultRemoteP11CryptService extends RemoteP11CryptService
{
    private static final String CMP_REQUEST_MIMETYPE = "application/pkixcmp";
    private static final String CMP_RESPONSE_MIMETYPE = "application/pkixcmp";

    private URL serverUrl;

    DefaultRemoteP11CryptService(String url)
    {
        ParamChecker.assertNotEmpty("url", url);

        try
        {
            this.serverUrl = new URL(url);
        } catch (MalformedURLException e)
        {
            throw new IllegalArgumentException("Invalid url: " + serverUrl);
        }
    }

    @Override
    public byte[] send(byte[] request)
    throws IOException
    {
        HttpURLConnection httpUrlConnection = (HttpURLConnection) serverUrl.openConnection();
        httpUrlConnection.setDoOutput(true);
        httpUrlConnection.setUseCaches(false);

        int size = request.length;

        httpUrlConnection.setRequestMethod("POST");
        httpUrlConnection.setRequestProperty("Content-Type", CMP_REQUEST_MIMETYPE);
        httpUrlConnection.setRequestProperty("Content-Length", java.lang.Integer.toString(size));
        OutputStream outputstream = httpUrlConnection.getOutputStream();
        outputstream.write(request);
        outputstream.flush();

        InputStream inputstream = null;
        try
        {
            inputstream = httpUrlConnection.getInputStream();
        }catch(IOException e)
        {
            InputStream errStream = httpUrlConnection.getErrorStream();
            if(errStream != null)
            {
                errStream.close();
            }
            throw e;
        }

        try
        {
            String responseContentType = httpUrlConnection.getContentType();
            boolean isValidContentType = false;
            if (responseContentType != null)
            {
                   if (responseContentType.equalsIgnoreCase(CMP_RESPONSE_MIMETYPE))
                   {
                       isValidContentType = true;
                }
            }
            if (isValidContentType == false)
            {
                throw new IOException("Bad Response: Mime type "
                        + responseContentType
                        + " not supported!");
            }

            byte[] buf = new byte[4096];
            ByteArrayOutputStream bytearrayoutputstream = new ByteArrayOutputStream();
            do
            {
                int j = inputstream.read(buf);
                if (j == -1)
                {
                    break;
                }
                bytearrayoutputstream.write(buf, 0, j);
            } while (true);

            return bytearrayoutputstream.toByteArray();
        }finally
        {
            inputstream.close();
        }
    }

    @Override
    public void refresh()
    throws SignerException
    {
    }

}
