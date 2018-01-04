package com.ovh.api;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.ovh.api.OvhApiException.OvhApiExceptionCause;

/**
 * Simple low level wrapper over the OVH REST API.
 * 
 * 
 * @author mbsk
 *
 */
public class OvhApi {
	
	private final OvhApiEndpoints endpoint;
	private final String appKey;
	private final String appSecret;
	private final String consumerKey;

	public OvhApi() throws OvhApiException {
		Map<String, String> env = System.getenv();
		if (env.containsKey("OVH_ENDPOINT") && env.containsKey("OVH_APPLICATION_KEY") && env.containsKey("OVH_APPLICATION_SECRET") && env.containsKey("OVH_CONSUMER_KEY")) {
			endpoint = OvhApiEndpoints.fromString(System.getenv("OVH_ENDPOINT"));
			appKey = System.getenv("OVH_APPLICATION_KEY");
			appSecret = System.getenv("OVH_APPLICATION_SECRET");
			consumerKey = System.getenv("OVH_CONSUMER_KEY");

			assertAllConfigNotNull();
			return;
		}

		// find the config file
		File configFile = Utils.getConfigFile("ovh.conf", System.getProperty("user.home") + "/ovh.conf", "/etc/ovh.conf");
		if (configFile == null) {
			throw new OvhApiException("environment variables OVH_ENDPOINT, OVH_APPLICATION_KEY, OVH_APPLICATION_SECRET, OVH_CONSUMER_KEY or configuration files ./ovh.conf, ~/ovh.conf, /etc/ovh.conf were not found", OvhApiExceptionCause.CONFIG_ERROR);
		}

		try {
			// read the configuration file
			Properties config = new Properties();
			config.load(new FileInputStream(configFile));

			// get the values
			endpoint = OvhApiEndpoints.fromString(config.getProperty("endpoint", null));
			appKey = config.getProperty("application_key", null);
			appSecret = config.getProperty("application_secret", null);
			consumerKey = config.getProperty("consumer_key", null);

			assertAllConfigNotNull();
		} catch (Exception e) {
			throw new OvhApiException(e.getMessage(), OvhApiExceptionCause.CONFIG_ERROR);
		}
	}

	public OvhApi(OvhApiEndpoints endpoint, String appKey, String appSecret, String consumerKey) {
		this.endpoint = endpoint;
		this.appKey = appKey;
		this.appSecret = appSecret;
		this.consumerKey = consumerKey;

		assertAllConfigNotNull();
	}

	private void assertAllConfigNotNull() {
		if (endpoint == null || appKey == null || appSecret == null || consumerKey == null) {
			throw new IllegalArgumentException("Constructor parameters cannot be null");
		}
	}
	
	public String get(String path) throws OvhApiException {
		assertAllConfigNotNull();
		return get(path, "", true);
	}
	
	public String get(String path, boolean needAuth) throws OvhApiException {
		assertAllConfigNotNull();
		return get(path, "", needAuth);
	}
	
	public String get(String path, String body, boolean needAuth) throws OvhApiException {
		assertAllConfigNotNull();
		return call("GET", body, appKey, appSecret, consumerKey, endpoint, path, needAuth);
	}
	
	public String put(String path, String body, boolean needAuth) throws OvhApiException {
		assertAllConfigNotNull();
		return call("PUT", body, appKey, appSecret, consumerKey, endpoint, path, needAuth);
	}
	
	public String post(String path, String body, boolean needAuth) throws OvhApiException {
		assertAllConfigNotNull();
		return call("POST", body, appKey, appSecret, consumerKey, endpoint, path, needAuth);
	}
	
	public String delete(String path, String body, boolean needAuth) throws OvhApiException {
		assertAllConfigNotNull();
		return call("DELETE", body, appKey, appSecret, consumerKey, endpoint, path, needAuth);
	}
	
    private String call(String method, String body, String appKey, String appSecret, String consumerKey, OvhApiEndpoints endpoint, String path, boolean needAuth) throws OvhApiException
    {
	
		try {
			
			URL url = new URL(new StringBuilder(endpoint.getUrl()).append(path).toString());

			// prepare 
			HttpURLConnection request = (HttpURLConnection) url.openConnection();
			request.setRequestMethod(method);
			request.setReadTimeout(30000);
			request.setConnectTimeout(30000);
			request.setRequestProperty("Content-Type", "application/json");
			request.setRequestProperty("X-Ovh-Application", appKey);
			// handle authentification
			if(needAuth) {
				// get timestamp from local system
				long timestamp = System.currentTimeMillis() / 1000;

				// build signature
				String toSign = new StringBuilder(appSecret)
									.append("+")
									.append(consumerKey)
									.append("+")
									.append(method)
									.append("+")
									.append(url)
									.append("+")
									.append(body)
									.append("+")
									.append(timestamp)
									.toString();
				String signature = new StringBuilder("$1$").append(HashSHA1(toSign)).toString();
				
				// set HTTP headers for authentication
				request.setRequestProperty("X-Ovh-Consumer", consumerKey);
				request.setRequestProperty("X-Ovh-Signature", signature);
				request.setRequestProperty("X-Ovh-Timestamp", Long.toString(timestamp));
			}
			
			if(body != null && !body.isEmpty())
            {
				request.setDoOutput(true);
                DataOutputStream out = new DataOutputStream(request.getOutputStream());
                out.writeBytes(body);
                out.flush();
                out.close();
            }
			
			
			String inputLine;
			BufferedReader in;
			int responseCode = request.getResponseCode();
			if (responseCode == 200) {
				in = new BufferedReader(new InputStreamReader(request.getInputStream()));
			} else {
				in = new BufferedReader(new InputStreamReader(request.getErrorStream()));
			}
			
			// build response
			StringBuilder response = new StringBuilder();
			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			
			if(responseCode == 200) {
				// return the raw JSON result
				return response.toString();
			} else if(responseCode == 400) {
				throw new OvhApiException(response.toString(), OvhApiExceptionCause.BAD_PARAMETERS_ERROR);
			} else if (responseCode == 403) {
				throw new OvhApiException(response.toString(), OvhApiExceptionCause.AUTH_ERROR);
			} else if (responseCode == 404) {
				throw new OvhApiException(response.toString(), OvhApiExceptionCause.RESSOURCE_NOT_FOUND);
			} else if (responseCode == 409) {
				throw new OvhApiException(response.toString(), OvhApiExceptionCause.RESSOURCE_CONFLICT_ERROR);
			} else {
				throw new OvhApiException(response.toString(), OvhApiExceptionCause.API_ERROR);
			}
			
		} catch (NoSuchAlgorithmException e) {
			throw new OvhApiException(e.getMessage(), OvhApiExceptionCause.INTERNAL_ERROR);
		} catch (IOException e) {
			throw new OvhApiException(e.getMessage(), OvhApiExceptionCause.INTERNAL_ERROR);
		}

	}
	
	public static String HashSHA1(String text) throws NoSuchAlgorithmException, UnsupportedEncodingException {
	    MessageDigest md;
        md = MessageDigest.getInstance("SHA-1");
        byte[] sha1hash = new byte[40];
        md.update(text.getBytes("iso-8859-1"), 0, text.length());
        sha1hash = md.digest();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < sha1hash.length; i++) {
            sb.append(Integer.toString((sha1hash[i] & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
	}

}
