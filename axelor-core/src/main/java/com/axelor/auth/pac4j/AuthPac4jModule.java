/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2021 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.auth.pac4j;

import com.axelor.app.AppSettings;
import com.axelor.auth.AuthWebModule;
import com.axelor.common.StringUtils;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.binder.AnnotatedBindingBuilder;
import com.google.inject.multibindings.Multibinder;
import io.buji.pac4j.context.ShiroSessionStore;
import io.buji.pac4j.engine.ShiroCallbackLogic;
import io.buji.pac4j.engine.ShiroSecurityLogic;
import io.buji.pac4j.filter.CallbackFilter;
import io.buji.pac4j.filter.LogoutFilter;
import io.buji.pac4j.filter.SecurityFilter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.FilterChain;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.shiro.authc.AuthenticationListener;
import org.apache.shiro.authc.pam.ModularRealmAuthenticator;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.pac4j.core.authorization.authorizer.Authorizer;
import org.pac4j.core.authorization.authorizer.RequireAnyRoleAuthorizer;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.core.http.adapter.J2ENopHttpActionAdapter;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.http.client.indirect.FormClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AuthPac4jModule extends AuthWebModule {

  public static final String CONFIG_AUTH_CALLBACK_URL = "auth.callback.url";
  public static final String CONFIG_AUTH_USER_PROVISIONING = "auth.user.provisioning";
  public static final String CONFIG_AUTH_USER_DEFAULT_GROUP = "auth.user.default.group";
  public static final String CONFIG_AUTH_USER_PRINCIPAL_ATTRIBUTE = "auth.user.principal.attribute";

  public static final String CONFIG_AUTH_LOGOUT_URL = "auth.logout.url";
  public static final String CONFIG_AUTH_LOGOUT_URL_PATTERN = "auth.logout.url.pattern";
  public static final String CONFIG_AUTH_LOGOUT_LOCAL = "auth.logout.local";
  public static final String CONFIG_AUTH_LOGOUT_CENTRAL = "auth.logout.central";

  protected static final String ROLE_HAS_USER = "_ROLE_HAS_USER";

  private static final String HASH_LOCATION_PARAMETER = "hash_location";

  @SuppressWarnings("rawtypes")
  private List<Client> clientList = new ArrayList<>();

  private static final Set<String> centralClientNames = new LinkedHashSet<>();

  private static final Map<String, Map<String, String>> clientInfo = new HashMap<>();

  private static String callbackUrl;
  private static boolean absCallbackUrlRequired;
  private static String logoutUrl;

  private static final Logger logger =
      LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public AuthPac4jModule(ServletContext servletContext) {
    super(servletContext);
    logger.info("Loading pac4j: {}", getClass().getSimpleName());
  }

  @Nullable
  public static Map<String, String> getClientInfo(String clientName) {
    return clientInfo.get(clientName);
  }

  protected static void setClientInfo(String clientName, Map<String, String> info) {
    clientInfo.put(clientName, info);
  }

  @Override
  protected void configureAuth() {
    configureClients();

    final Multibinder<AuthenticationListener> listenerMultibinder =
        Multibinder.newSetBinder(binder(), AuthenticationListener.class);
    listenerMultibinder.addBinding().to(AuthPac4jListener.class);

    bind(ConfigSupplier.class);
    bindRealm().to(AuthPac4jRealm.class);
    addFilterChain("/logout", Key.get(AxelorLogoutFilter.class));
    addFilterChain("/callback", Key.get(AxelorCallbackFilter.class));
    addFilterChain("/**", Key.get(AxelorSecurityFilter.class));
  }

  protected abstract void configureClients();

  protected void addClient(FormClient client) {
    clientList.add(0, client);
    logger.info("Added local client: {}", client.getName());
  }

  protected void addClient(Client<?, ?> client) {
    clientList.add(client);
    centralClientNames.add(client.getName());
    logger.info("Added central client: {}", client.getName());
  }

  public static Set<String> getCentralClients() {
    return centralClientNames;
  }

  @Provides
  @SuppressWarnings("rawtypes")
  public List<Client> getClientList() {
    return clientList;
  }

  public static String getRelativeBaseURL() {
    String base = AppSettings.get().getBaseURL();
    return URI.create(base).getPath();
  }

  public static String getCallbackUrl() {
    if (callbackUrl == null) {
      if (isEnabled()) {
        final AppSettings settings = AppSettings.get();
        callbackUrl = settings.get(CONFIG_AUTH_CALLBACK_URL, null);

        // Backward-compatible CAS configuration
        if (StringUtils.isBlank(callbackUrl) && AuthPac4jModuleCas.isEnabled()) {
          callbackUrl = settings.get(AuthPac4jModuleCas.CONFIG_CAS_SERVICE, null);
        }

        if (StringUtils.isBlank(callbackUrl)) {
          String base = AppSettings.get().getBaseURL();
          if (!absCallbackUrlRequired) {
            base = URI.create(base).getPath();
          }
          callbackUrl = base + "/callback";
        }
      } else {
        callbackUrl = "";
      }
    }

    return callbackUrl;
  }

  public static void requireAbsCallbackUrl() {
    absCallbackUrlRequired = true;
  }

  public static String getLogoutUrl() {
    if (logoutUrl == null) {
      // Backward-compatible CAS configuration
      final AppSettings settings = AppSettings.get();
      logoutUrl = settings.get(CONFIG_AUTH_LOGOUT_URL, null);
      if (StringUtils.isBlank(logoutUrl)) {
        logoutUrl =
            AuthPac4jModuleCas.isEnabled()
                ? settings.get(AuthPac4jModuleCas.CONFIG_CAS_LOGOUT_URL, getRelativeBaseURL())
                : getRelativeBaseURL();
      }
      if (StringUtils.isBlank(logoutUrl)) {
        logoutUrl = ".";
      }
    }

    return logoutUrl;
  }

  public static boolean isEnabled() {
    return AuthPac4jModuleOidc.isEnabled()
        || AuthPac4jModuleOAuth.isEnabled()
        || AuthPac4jModuleSaml.isEnabled()
        || AuthPac4jModuleCas.isEnabled();
  }

  @Override
  protected void bindWebSecurityManager(AnnotatedBindingBuilder<? super WebSecurityManager> bind) {
    bind.to(DefaultWebSecurityManager.class);
  }

  @Provides
  protected DefaultWebSecurityManager provideDefaultSecurityManager(
      Collection<Realm> realms, Set<AuthenticationListener> authenticationListeners) {
    DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager(realms);
    ModularRealmAuthenticator authenticator = new ModularRealmAuthenticator();
    authenticator.setRealms(realms);
    authenticator.setAuthenticationListeners(authenticationListeners);
    securityManager.setAuthenticator(authenticator);
    return securityManager;
  }

  protected static boolean isXHR(WebContext context) {
    return context instanceof J2EContext && isXHR(((J2EContext) context).getRequest());
  }

  protected static boolean isXHR(HttpServletRequest request) {
    return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
        || "application/json".equals(request.getHeader("Accept"))
        || "application/json".equals(request.getHeader("Content-Type"));
  }

  @Singleton
  private static class ConfigSupplier implements Supplier<Config> {
    private static Config config;

    @Inject
    public ConfigSupplier(@SuppressWarnings("rawtypes") List<Client> clientList) {
      if (config != null) {
        return;
      }

      final Clients clients = new Clients(getCallbackUrl(), clientList);
      final Authorizer<CommonProfile> authorizer = new RequireAnyRoleAuthorizer<>(ROLE_HAS_USER);

      config = new Config(clients, ImmutableMap.of("auth", authorizer));
    }

    @Override
    public Config get() {
      return config;
    }
  }

  private static class AxelorLogoutFilter extends LogoutFilter {

    @Inject
    public AxelorLogoutFilter(ConfigSupplier configSupplier) {
      final AppSettings settings = AppSettings.get();
      final String logoutUrlPattern = settings.get(CONFIG_AUTH_LOGOUT_URL_PATTERN, null);
      final boolean localLogout = settings.getBoolean(CONFIG_AUTH_LOGOUT_LOCAL, true);
      final boolean centralLogout = settings.getBoolean(CONFIG_AUTH_LOGOUT_CENTRAL, false);
      final Config config = configSupplier.get();

      setConfig(config);
      setDefaultUrl(getLogoutUrl());
      setLogoutUrlPattern(logoutUrlPattern);
      setLocalLogout(localLogout);
      setCentralLogout(centralLogout);
    }

    @Override
    public void doFilter(
        final ServletRequest servletRequest,
        final ServletResponse servletResponse,
        final FilterChain filterChain)
        throws IOException, ServletException {

      CommonHelper.assertNotNull("logoutLogic", getLogoutLogic());
      CommonHelper.assertNotNull("config", getConfig());

      final HttpServletRequest request = (HttpServletRequest) servletRequest;
      final HttpServletResponse response = (HttpServletResponse) servletResponse;
      @SuppressWarnings("unchecked")
      final SessionStore<J2EContext> sessionStore = getConfig().getSessionStore();
      final J2EContext context =
          new J2EContext(
              request, response, sessionStore != null ? sessionStore : ShiroSessionStore.INSTANCE);

      // Destroy web session.
      getLogoutLogic()
          .perform(
              context,
              getConfig(),
              J2ENopHttpActionAdapter.INSTANCE,
              getDefaultUrl(),
              getLogoutUrlPattern(),
              getLocalLogout(),
              true,
              getCentralLogout());
    }
  }

  private static class AxelorCallbackFilter extends CallbackFilter {

    @Inject
    public AxelorCallbackFilter(ConfigSupplier configSupplier) {
      final Config config = configSupplier.get();
      setConfig(config);

      final AppSettings settings = AppSettings.get();
      final String defaultUrl = settings.getBaseURL();

      if (StringUtils.notBlank(defaultUrl)) {
        setDefaultUrl(defaultUrl);
      }

      setDefaultClient(config.getClients().getClients().get(0).getName());
      setCallbackLogic(
          new ShiroCallbackLogic<Object, J2EContext>() {

            @SuppressWarnings("unchecked")
            @Override
            protected HttpAction redirectToOriginallyRequestedUrl(
                J2EContext context, String defaultUrl) {

              // if xhr, return status code only
              if (isXHR(context)) {
                return HttpAction.status(HttpConstants.OK, context);
              }

              final String requestedUrl =
                  (String) context.getSessionStore().get(context, Pac4jConstants.REQUESTED_URL);

              String redirectUrl = defaultUrl;
              if (StringUtils.notBlank(requestedUrl)) {
                context.getSessionStore().set(context, Pac4jConstants.REQUESTED_URL, null);
                redirectUrl = requestedUrl;
              }

              String hashLocation = context.getRequestParameter(HASH_LOCATION_PARAMETER);
              if (StringUtils.isBlank(hashLocation)) {
                hashLocation =
                    (String) context.getSessionStore().get(context, HASH_LOCATION_PARAMETER);
              }
              if (StringUtils.notBlank(hashLocation)) {
                redirectUrl += hashLocation;
              }

              logger.debug("redirectUrl: {}", redirectUrl);
              return HttpAction.redirect(context, redirectUrl);
            }
          });
    }
  }

  private static class AxelorSecurityFilter extends SecurityFilter {

    @Inject
    public AxelorSecurityFilter(ConfigSupplier configSupplier) {
      final Config config = configSupplier.get();
      setConfig(config);
      setAuthorizers("auth");

      final String clientNames =
          config
              .getClients()
              .getClients()
              .stream()
              .map(Client::getName)
              .collect(Collectors.joining(","));
      setClients(clientNames);

      setSecurityLogic(
          new ShiroSecurityLogic<Object, J2EContext>() {

            // Don't save requested URL if redirected to a non-default central client,
            // so that the requested URL saved before redirection will be used instead.
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            protected void saveRequestedUrl(J2EContext context, List<Client> currentClients) {
              final String hashLocation = context.getRequestParameter(HASH_LOCATION_PARAMETER);
              if (StringUtils.notBlank(hashLocation)) {
                context.getSessionStore().set(context, HASH_LOCATION_PARAMETER, hashLocation);
              }

              final String clientName =
                  context.getRequestParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER);
              if (StringUtils.isBlank(clientName)
                  || currentClients.size() != 1
                  || !centralClientNames.contains(currentClients.get(0).getName())) {
                super.saveRequestedUrl(context, currentClients);
              }
            }
          });
    }
  }
}
