package org.marklogic.example;

import com.marklogic.client.DatabaseClientFactory.SAMLAuthContext;
import com.okta.authn.sdk.AuthenticationException;
import com.okta.authn.sdk.AuthenticationStateHandlerAdapter;
import com.okta.authn.sdk.client.AuthenticationClient;
import com.okta.authn.sdk.client.AuthenticationClients;
import com.okta.authn.sdk.resource.AuthenticationResponse;
import com.okta.commons.http.config.Proxy;
import com.okta.commons.lang.Strings;
import com.onelogin.sdk.conn.Client;
import com.onelogin.sdk.model.SAMLEndpointResponse;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.FormElement;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;

public class SAMLClientAuthorizer {
    public static SAMLAuthContext makeOktaSAMLAuthContext(String username, String password, String domain, String applicationEmbedLink) {

        SAMLClientAuthorizer authorizer =
                new SAMLClientAuthorizer(username, password, domain, applicationEmbedLink);

        return new SAMLAuthContext(authorizer::authorizeOkta);
    }
    public static SAMLAuthContext makeOneLoginAMLAuthContext(String username, String password, String subdomain, String appId) {

        SAMLClientAuthorizer authorizer =
                new SAMLClientAuthorizer(username, password, subdomain, appId);

        return new SAMLAuthContext(authorizer::authorizeOneLogin);
    }

    public void setTokenExpiryTimestamp(Date tokenExpiryTimestamp) {
        this.tokenExpiryTimestamp = tokenExpiryTimestamp;
    }

    public Date getTokenExpiryTimestamp() {
        return tokenExpiryTimestamp;
    }

    private Date tokenExpiryTimestamp;

    public void setAuthorizationToken(String authorizationToken) {
        this.authorizationToken = authorizationToken;
    }

    String authorizationToken;
    private String username;
    private String password;
    private String domain;
    /*
    represents applicationEmbededLink for Okta and ApplicationID in case of OneLogin
     */
    private String applicationEmbedLink;


    public SAMLClientAuthorizer(
            String username, String password, String domain, String applicationEmbedLink
    ) {
        this.username = username;
        this.password = password;
        this.applicationEmbedLink = applicationEmbedLink;
        this.domain = domain;

    }


    // the callback to pass to the SAMLAuthContext() constructor
    public SAMLAuthContext.ExpiringSAMLAuth authorizeOkta(SAMLAuthContext.ExpiringSAMLAuth old) {
        Proxy proxy = new Proxy("127.0.0.1", 8080);//proxy set in order to monitor request through Burp
        AuthenticationClient client = AuthenticationClients.builder()
                .setOrgUrl("https://"+domain)
                .setProxy(proxy)
                .build();
        try {
            client.authenticate(username, password.toCharArray(), null, new AuthenticationStateHandlerAdapter() {

                @Override
                public void handleSuccess(AuthenticationResponse successResponse) {
                    if (Strings.hasLength(successResponse.getSessionToken())) {
                        /*Parse Okta html response using Jsoup to get SAMLResponse*/
                        String samlResponse = getIDPToken(applicationEmbedLink, successResponse.getSessionToken());
                        if (samlResponse != null) setAuthorizationToken(samlResponse);
                        setTokenExpiryTimestamp(successResponse.getExpiresAt());
                    }
                }
                @Override
                public void handleUnknown(AuthenticationResponse unknownResponse) {

                }
            });
        } catch (AuthenticationException e) {
            throw new RuntimeException("failed to create authentication request");
        }
        return SAMLAuthContext.newExpiringSAMLAuth(authorizationToken, getTokenExpiryTimestamp().toInstant());
    }
    public SAMLAuthContext.ExpiringSAMLAuth authorizeOneLogin(SAMLAuthContext.ExpiringSAMLAuth old) {
        try {
            //create new OAuth 2.0 Api access client
            Client client = new Client();
            /*get Access_token through a call to REST endpoint api.us.onelogin.com/auth/oauth2/token
            API access Credential are required and they will be read from src/main/resources/onelogin.sdk.properties
             */
            client.getAccessToken();
            //Generates a SAML Assertion
            SAMLEndpointResponse samlEndpointResponse = client.getSAMLAssertion(username, password, applicationEmbedLink, domain);
            //Set SAML authorizationToken to AuthorizerCallback
            setAuthorizationToken(samlEndpointResponse.getSAMLResponse());

        } catch (Exception e) {
            throw new RuntimeException("failed to create authentication request");
        }
        //access token expires in 36,000 seconds, 600 minutes, or 10 hours.
        return SAMLAuthContext.newExpiringSAMLAuth(authorizationToken, Instant.now().plusSeconds(36000));
    }

    public static String getIDPToken(String embedLink, String sessionToken) {
        String token = null;
        String stage = "form request";
        try {
            String formUrl = embedLink + "?sessionToken=" + sessionToken;
            Connection.Response formResponse = Jsoup
                    .connect(formUrl)
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8080)))
                    .userAgent("Mozilla/5.0")
                    .method(Connection.Method.GET)
                    .execute();
            if (formResponse.statusCode() >= 400) {
                String msg = new StringBuilder()
                        .append("bad form response from IDP: ")
                        .append(formResponse.statusCode())
                        .append(" ")
                        .append(formResponse.statusMessage())
                        .append("\n")
                        .append(formResponse.body())
                        .append("\n")
                        .toString();
                throw new RuntimeException(msg);
            }
            org.jsoup.nodes.Document formDoc = formResponse.parse();
            stage = "form response";

            List<FormElement> forms = formDoc.select("form[method=\"POST\"][action]").forms();
            if (forms.size() != 1) {
                String msg = new StringBuilder()
                        .append("could not find one form:\n")
                        .append(formResponse.body())
                        .append("\n")
                        .toString();
                throw new RuntimeException(msg);
            }
            FormElement form = forms.get(0);
            Elements fields = form.elements();
            if (fields.size() == 0) {
                String msg = new StringBuilder()
                        .append("could not find form fields:\n")
                        .append(formResponse.body())
                        .append("\n")
                        .toString();
                throw new RuntimeException(msg);
            }

            for (Element field : fields) {
                if (field.attributes().hasKey("name") && "SAMLResponse".equals(field.attributes().get("name"))) {
                    token = field.val();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to get SAML response");
        }
        return token;
    }
}

