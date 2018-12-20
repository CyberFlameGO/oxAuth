/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletContext;

import org.codehaus.jackson.map.ObjectMapper;
import org.gluu.site.ldap.OperationsFacade;
import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.jboss.weld.util.reflection.ParameterizedTypeImpl;
import org.slf4j.Logger;
import org.xdi.exception.ConfigurationException;
import org.xdi.model.SimpleProperty;
import org.xdi.model.custom.script.CustomScriptType;
import org.xdi.model.ldap.GluuLdapConfiguration;
import org.xdi.oxauth.model.appliance.GluuAppliance;
import org.xdi.oxauth.model.auth.AuthenticationMode;
import org.xdi.oxauth.model.config.ConfigurationFactory;
import org.xdi.oxauth.model.config.oxIDPAuthConf;
import org.xdi.oxauth.model.event.ApplicationInitializedEvent;
import org.xdi.oxauth.model.util.SecurityProviderUtility;
import org.xdi.oxauth.service.cdi.event.AuthConfigurationEvent;
import org.xdi.oxauth.service.cdi.event.ReloadAuthScript;
import org.xdi.oxauth.service.external.ExternalAuthenticationService;
import org.xdi.oxauth.service.logger.LoggerService;
import org.xdi.oxauth.service.status.ldap.LdapStatusTimer;
import org.xdi.service.PythonService;
import org.xdi.service.cdi.async.Asynchronous;
import org.xdi.service.cdi.event.ApplicationInitialized;
import org.xdi.service.cdi.event.LdapConfigurationReload;
import org.xdi.service.cdi.event.Scheduled;
import org.xdi.service.cdi.util.CdiUtil;
import org.xdi.service.custom.lib.CustomLibrariesLoader;
import org.xdi.service.custom.script.CustomScriptManager;
import org.xdi.service.ldap.LdapConnectionProviders;
import org.xdi.service.ldap.LdapConnectionService;
import org.xdi.service.metric.inject.ReportMetric;
import org.xdi.service.timer.QuartzSchedulerManager;
import org.xdi.service.timer.event.TimerEvent;
import org.xdi.service.timer.schedule.TimerSchedule;
import org.xdi.util.StringHelper;
import org.xdi.util.properties.FileConfiguration;
import org.xdi.util.security.StringEncrypter;
import org.xdi.util.security.StringEncrypter.EncryptionException;

import com.unboundid.ldap.sdk.ResultCode;

/**
 * @author Javier Rojas Blum
 * @author Yuriy Movchan
 * @author Yuriy Zabrovarnyy
 * @version 0.1, 24/10/2011
 */
@ApplicationScoped
@Named
public class AppInitializer {

	private final static String EVENT_TYPE = "AppInitializerTimerEvent";
    private final static int DEFAULT_INTERVAL = 30; // 30 seconds

    public static final String LDAP_AUTH_CONFIG_NAME = "ldapAuthConfig";
    public static final String LDAP_METRIC_CONFIG_GROUP_NAME = "metric";

    public static final String LDAP_ENTRY_MANAGER_NAME = "ldapEntryManager";
    public static final String LDAP_METRIC_ENTRY_MANAGER_NAME = "ldapMetricEntryManager";
    public static final String LDAP_AUTH_ENTRY_MANAGER_NAME = "ldapAuthEntryManager";

    @Inject
    private Logger log;

	@Inject
	private BeanManager beanManager;

	@Inject
	private Event<String> event;

    @Inject
    private Event<ApplicationInitializedEvent> eventApplicationInitialized;

	@Inject
	private Event<TimerEvent> timerEvent;

	@Inject @Named(LDAP_ENTRY_MANAGER_NAME)
	private Instance<LdapEntryManager> ldapEntryManagerInstance;

	@Inject  @Named(LDAP_METRIC_ENTRY_MANAGER_NAME) @ReportMetric
    private Instance<LdapEntryManager> ldapMetricEntryManagerInstance;
	
	@Inject @Named(LDAP_AUTH_ENTRY_MANAGER_NAME)
	private Instance<List<LdapEntryManager>> ldapAuthEntryManagerInstance;

	@Inject @Named(LDAP_AUTH_CONFIG_NAME)
	private Instance<List<GluuLdapConfiguration>> ldapAuthConfigInstance;

	@Inject
	private Instance<AuthenticationMode> authenticationModeInstance;

	@Inject
	private Instance<EncryptionService> encryptionServiceInstance;

    @Inject
    private ApplianceService applianceService;

    @Inject
    private PythonService pythonService;

    @Inject
    private MetricService metricService;

    @Inject
    private CustomScriptManager customScriptManager;

	@Inject
	private ConfigurationFactory configurationFactory;
	
	@Inject
	private CleanerTimer cleanerTimer;
	
	@Inject
	private KeyGeneratorTimer keyGeneratorTimer;

	@Inject
	private CustomLibrariesLoader customLibrariesLoader;

	@Inject
	private LdapStatusTimer ldapStatusTimer;
	
	@Inject
	private QuartzSchedulerManager quartzSchedulerManager;

	@Inject
	private LoggerService loggerService;

	private List<GluuLdapConfiguration> ldapAuthConfigs;

	private LdapConnectionProviders connectionProviders;
    private LdapConnectionProviders metricConnectionProviders;

	private List<LdapConnectionService> authConnectionProviders;
	private List<LdapConnectionService> authBindConnectionProviders;

    private AtomicBoolean isActive;
	private long lastFinishedTime;
	private AuthenticationMode authenticationMode;

	@PostConstruct
    public void createApplicationComponents() {
    	SecurityProviderUtility.installBCProvider();
    }

    public void applicationInitialized(@Observes @Initialized(ApplicationScoped.class) Object init) {
    	customLibrariesLoader.init();

    	this.connectionProviders = createConnectionProvider((String) null, true);
    	this.metricConnectionProviders = createConnectionProvider(LDAP_METRIC_CONFIG_GROUP_NAME, false);

    	configurationFactory.create();

        LdapEntryManager localLdapEntryManager = ldapEntryManagerInstance.get();
        List<GluuLdapConfiguration> ldapAuthConfigs = loadLdapAuthConfigs(localLdapEntryManager);
        createAuthConnectionProviders(ldapAuthConfigs);

        setDefaultAuthenticationMethod(localLdapEntryManager);

		// Initialize python interpreter
        pythonService.initPythonInterpreter(configurationFactory.getLdapConfiguration().getString("pythonModulesDir", null));

		// Initialize script manager
        List<CustomScriptType> supportedCustomScriptTypes = Arrays.asList(CustomScriptType.PERSON_AUTHENTICATION, CustomScriptType.CONSENT_GATHERING,
        		CustomScriptType.CLIENT_REGISTRATION, CustomScriptType.ID_GENERATOR, CustomScriptType.UMA_RPT_POLICY, CustomScriptType.UMA_CLAIMS_GATHERING,
				CustomScriptType.APPLICATION_SESSION, CustomScriptType.DYNAMIC_SCOPE, CustomScriptType.INTROSPECTION);

        // Start timer
        initSchedulerService();

        // Schedule timer tasks
        metricService.initTimer();
        configurationFactory.initTimer();
        loggerService.initTimer();
        ldapStatusTimer.initTimer();
        cleanerTimer.initTimer();
        customScriptManager.initTimer(supportedCustomScriptTypes);
        keyGeneratorTimer.initTimer();
        initTimer();

        // Notify plugins about finish application initialization
        eventApplicationInitialized.select(ApplicationInitialized.Literal.APPLICATION).fire(new ApplicationInitializedEvent());
	}

    protected void initSchedulerService() {
        quartzSchedulerManager.start();

        String disableScheduler = System.getProperties().getProperty("gluu.disable.scheduler");
        if ((disableScheduler != null) && Boolean.valueOf(disableScheduler)) {
            this.log.warn("Suspending Quartz Scheduler Service...");
            quartzSchedulerManager.standby();
            return;
        }
    }

    @Produces @ApplicationScoped
	public StringEncrypter getStringEncrypter() {
		String encodeSalt = configurationFactory.getCryptoConfigurationSalt();
    	
    	if (StringHelper.isEmpty(encodeSalt)) {
    		throw new ConfigurationException("Encode salt isn't defined");
    	}
    	
    	try {
    		StringEncrypter stringEncrypter = StringEncrypter.instance(encodeSalt);
    		
    		return stringEncrypter;
		} catch (EncryptionException ex) {
    		throw new ConfigurationException("Failed to create StringEncrypter instance");
		}
	}

    public void initTimer() {
		this.isActive = new AtomicBoolean(false);
		this.lastFinishedTime = System.currentTimeMillis();

		timerEvent.fire(new TimerEvent(new TimerSchedule(60, DEFAULT_INTERVAL), new AuthConfigurationEvent(),
				Scheduled.Literal.INSTANCE));
    }

    public void destroy(@Observes @BeforeDestroyed(ApplicationScoped.class) ServletContext init) {
    	log.info("Closing LDAP connection at server shutdown...");
        LdapEntryManager ldapEntryManager = ldapEntryManagerInstance.get();
        closeLdapEntryManager(ldapEntryManager, LDAP_ENTRY_MANAGER_NAME);

        LdapEntryManager ldapMetricEntryManager = ldapMetricEntryManagerInstance.get();
        closeLdapEntryManager(ldapMetricEntryManager, LDAP_METRIC_ENTRY_MANAGER_NAME);
        
    	List<LdapEntryManager> ldapAuthEntryManagers = ldapAuthEntryManagerInstance.get();
        closeLdapAuthEntryManagers(ldapAuthEntryManagers);
    }
    
    @Asynchronous
    public void reloadConfigurationTimerEvent(@Observes @Scheduled AuthConfigurationEvent authConfigurationEvent) {
		if (this.isActive.get()) {
			return;
		}

		if (!this.isActive.compareAndSet(false, true)) {
			return;
		}

		try {
			reloadConfiguration();
		} catch (Throwable ex) {
			log.error("Exception happened while reloading application configuration", ex);
		} finally {
			this.isActive.set(false);
			this.lastFinishedTime = System.currentTimeMillis();
		}
	}

	private void reloadConfiguration() {
        LdapEntryManager localLdapEntryManager = ldapEntryManagerInstance.get();

        log.trace("Attempting to use {}: {}", LDAP_ENTRY_MANAGER_NAME, localLdapEntryManager.getLdapOperationService());
		List<GluuLdapConfiguration> newLdapAuthConfigs = loadLdapAuthConfigs(localLdapEntryManager);
		
		if (!this.ldapAuthConfigs.equals(newLdapAuthConfigs)) {
			recreateLdapAuthEntryManagers(newLdapAuthConfigs);
			event.select(ReloadAuthScript.Literal.INSTANCE).fire(ExternalAuthenticationService.MODIFIED_INTERNAL_TYPES_EVENT_TYPE);
		}

		setDefaultAuthenticationMethod(localLdapEntryManager);
	}

	/*
	 * Utility method which can be used in custom scripts
	 */
	public LdapEntryManager createLdapAuthEntryManager(GluuLdapConfiguration ldapAuthConfig) {
    	LdapConnectionProviders ldapConnectionProviders = createAuthConnectionProviders(ldapAuthConfig);

    	LdapEntryManager ldapAuthEntryManager = new LdapEntryManager(new OperationsFacade(ldapConnectionProviders.getConnectionProvider(), ldapConnectionProviders.getConnectionBindProvider()));
	    log.debug("Created custom authentication LdapEntryManager: {}", ldapAuthEntryManager);
	        
		return ldapAuthEntryManager;
	}

    @Produces @ApplicationScoped @Named(LDAP_AUTH_CONFIG_NAME)
    public List<GluuLdapConfiguration> createLdapAuthConfigs() {
    	return ldapAuthConfigs;
    }

    @Produces @ApplicationScoped @Named(LDAP_AUTH_ENTRY_MANAGER_NAME)
	public List<LdapEntryManager> createLdapAuthEntryManager() {
		List<LdapEntryManager> ldapAuthEntryManagers = new ArrayList<LdapEntryManager>();
		if (this.ldapAuthConfigs.size() == 0) {
			return ldapAuthEntryManagers;
		}

		for (int i = 0; i < this.ldapAuthConfigs.size(); i++) {
			LdapEntryManager ldapAuthEntryManager = new LdapEntryManager(new OperationsFacade(this.authConnectionProviders.get(i), this.authBindConnectionProviders.get(i)));
	        log.debug("Created {}#{}: {}", new Object[] { LDAP_AUTH_ENTRY_MANAGER_NAME, i, ldapAuthEntryManager });
	        
	        ldapAuthEntryManagers.add(ldapAuthEntryManager);
		}

		return ldapAuthEntryManagers;
	}

    @Produces @Named(LDAP_ENTRY_MANAGER_NAME) @ApplicationScoped
    public LdapEntryManager createLdapEntryManager() {
        LdapEntryManager ldapEntryManager = new LdapEntryManager(new OperationsFacade(this.connectionProviders.getConnectionProvider(), this.connectionProviders.getConnectionBindProvider()));
        log.info("Created {}:{} with provider {}", LDAP_ENTRY_MANAGER_NAME, ldapEntryManager, ldapEntryManager.getLdapOperationService().getConnectionProvider() );

        return ldapEntryManager;
    }

    @Produces @Named(LDAP_METRIC_ENTRY_MANAGER_NAME) @ReportMetric @ApplicationScoped
    public LdapEntryManager createLdapMetricEntryManager() {
        LdapEntryManager ldapMetricEntryManager = new LdapEntryManager(new OperationsFacade(this.metricConnectionProviders.getConnectionProvider()));
        log.info("Created {}:{} with provider {}", LDAP_METRIC_ENTRY_MANAGER_NAME, ldapMetricEntryManager, ldapMetricEntryManager.getLdapOperationService().getConnectionProvider());

        return ldapMetricEntryManager;
    }

    public void recreateLdapEntryManager(@Observes @LdapConfigurationReload String event) {
        this.connectionProviders = recreateLdapEntryManagerImpl(LDAP_ENTRY_MANAGER_NAME, null, true); 
        forceCreateNewEntryManager(ldapEntryManagerInstance, LDAP_ENTRY_MANAGER_NAME);

        this.metricConnectionProviders = recreateLdapEntryManagerImpl(LDAP_METRIC_ENTRY_MANAGER_NAME, LDAP_METRIC_CONFIG_GROUP_NAME, false, ReportMetric.Literal.INSTANCE);
        forceCreateNewEntryManager(ldapMetricEntryManagerInstance, LDAP_METRIC_ENTRY_MANAGER_NAME);
    }

    protected <T> LdapConnectionProviders recreateLdapEntryManagerImpl(String entryManagerName, String configId, boolean createBind, Annotation... qualifiers) {
        // Get existing application scoped instance
    	LdapEntryManager oldLdapEntryManager = CdiUtil.getContextBean(beanManager, LdapEntryManager.class, entryManagerName, qualifiers);

    	// Recreate components
    	LdapConnectionProviders createConnectionProviders = createConnectionProvider(configId, createBind);

        // Close existing connections
    	closeLdapEntryManager(oldLdapEntryManager, entryManagerName);
        
        return createConnectionProviders;
    }

    protected <T> void forceCreateNewEntryManager(Instance<T> instance, String entryManagerName) {
        // Force to create new bean
    	T ldapEntryManager = instance.get();
    	instance.destroy(ldapEntryManager);
        log.info("Recreated instance {}: {}", entryManagerName, ldapEntryManager);
    }

    private LdapConnectionProviders createConnectionProvider(String configId, boolean createBind) {
        Properties connectionProperties = getLdapConfigProperties(configId);
        String logConfigId = StringHelper.isEmpty(configId) ? "" : configId + "-";

        LdapConnectionService connectionProvider = createConnectionProvider(connectionProperties);
        if (!ResultCode.SUCCESS.equals(connectionProvider.getCreationResultCode())) {
    		throw new ConfigurationException("Failed to create LDAP connection pool!");
        }
    	log.debug("Created {}connectionProvider: {}", logConfigId, connectionProvider);

    	LdapConnectionService bindConnectionProvider = null;
    	if (createBind) {
            Properties bindConnectionProperties = prepareBindConnectionProperties(connectionProperties);
            bindConnectionProvider = createBindConnectionProvider(bindConnectionProperties, connectionProperties);
            if (!ResultCode.SUCCESS.equals(bindConnectionProvider.getCreationResultCode())) {
        		throw new ConfigurationException("Failed to create LDAP connection pool!");
            }
            log.debug("Created {}bindConnectionProvider: {}", logConfigId, bindConnectionProvider);
        }
        
        return new LdapConnectionProviders(connectionProvider, bindConnectionProvider);
    }

    protected Properties getLdapConfigProperties(String configId) {
        Properties connectionProperties = (Properties) configurationFactory.getLdapConfiguration().getProperties();
        if (StringHelper.isNotEmpty(configId)) {
            // Replace properties names 'configId.xyz' to 'configId.xyz' in order to override default values
            connectionProperties = (Properties) connectionProperties.clone();
            
            String baseGroup = configId + ".";
            for (Object key : connectionProperties.keySet()) {
                String propertyName = (String) key;
                if (propertyName.startsWith(baseGroup)) {
                    propertyName = propertyName.substring(baseGroup.length());
                    
                    Object value = connectionProperties.get(key);
                    connectionProperties.put(propertyName, value);
                }
            }
        }

        return connectionProperties;
    }

	private void closeLdapEntryManager(LdapEntryManager oldLdapEntryManager, String entryManagerName) {
        // Close existing connections
        if ((oldLdapEntryManager != null) && (oldLdapEntryManager.getLdapOperationService() != null)) {
            log.debug("Attempting to destroy {}:{} with provider {}", entryManagerName, oldLdapEntryManager, oldLdapEntryManager.getLdapOperationService().getConnectionProvider());
            oldLdapEntryManager.destroy();
            log.debug("Destroyed {}:{} with provider {}", entryManagerName, oldLdapEntryManager, oldLdapEntryManager.getLdapOperationService().getConnectionProvider());
        }
	}

    public void recreateLdapAuthEntryManagers(List<GluuLdapConfiguration> newLdapAuthConfigs) {
    	// Get existing application scoped instance
		List<LdapEntryManager> oldLdapAuthEntryManagers = CdiUtil.getContextBean(beanManager,
				new ParameterizedTypeImpl(List.class, LdapEntryManager.class), LDAP_AUTH_ENTRY_MANAGER_NAME);

    	// Recreate components
        createAuthConnectionProviders(newLdapAuthConfigs);
        
        // Close existing connections
        closeLdapAuthEntryManagers(oldLdapAuthEntryManagers);

		// Destroy old Ldap auth entry managers
		for (LdapEntryManager oldLdapAuthEntryManager : oldLdapAuthEntryManagers) {
	    	log.debug("Attempting to destroy {}: {}", LDAP_AUTH_ENTRY_MANAGER_NAME, oldLdapAuthEntryManager);
			oldLdapAuthEntryManager.destroy();
	        log.debug("Destroyed {}: {}", LDAP_AUTH_ENTRY_MANAGER_NAME, oldLdapAuthEntryManager);
		}
		
        // Force to create new bean
    	List<LdapEntryManager> ldapAuthEntryManagers = ldapAuthEntryManagerInstance.get();
    	ldapAuthEntryManagerInstance.destroy(ldapAuthEntryManagers);
        log.info("Recreated instance {}: {}", LDAP_AUTH_ENTRY_MANAGER_NAME, ldapAuthEntryManagers);
    }

    private void createAuthConnectionProviders(List<GluuLdapConfiguration> newLdapAuthConfigs) {
    	// Backup current references to objects to allow shutdown properly
    	List<GluuLdapConfiguration> oldLdapAuthConfigs = ldapAuthConfigInstance.get();

    	List<LdapConnectionService> tmpAuthConnectionProviders = new ArrayList<LdapConnectionService>();
    	List<LdapConnectionService> tmpAuthBindConnectionProviders = new ArrayList<LdapConnectionService>();

    	// Prepare connection providers per LDAP authentication configuration
        for (GluuLdapConfiguration ldapAuthConfig : newLdapAuthConfigs) {
        	LdapConnectionProviders ldapConnectionProviders = createAuthConnectionProviders(ldapAuthConfig);

	        tmpAuthConnectionProviders.add(ldapConnectionProviders.getConnectionProvider());
	        tmpAuthBindConnectionProviders.add(ldapConnectionProviders.getConnectionBindProvider());
    	}

		this.ldapAuthConfigs = newLdapAuthConfigs;
		this.authConnectionProviders = tmpAuthConnectionProviders;
    	this.authBindConnectionProviders = tmpAuthBindConnectionProviders;

		ldapAuthConfigInstance.destroy(oldLdapAuthConfigs);
    }

	private void closeLdapAuthEntryManagers(List<LdapEntryManager> oldLdapAuthEntryManagers) {
		// Close existing connections
		for (LdapEntryManager oldLdapAuthEntryManager : oldLdapAuthEntryManagers) {
	    	log.debug("Attempting to destroy {}: {}", LDAP_AUTH_ENTRY_MANAGER_NAME, oldLdapAuthEntryManager);
			oldLdapAuthEntryManager.destroy();
	        log.debug("Destroyed {}: {}", LDAP_AUTH_ENTRY_MANAGER_NAME, oldLdapAuthEntryManager);
		}
	}

    public LdapConnectionProviders createAuthConnectionProviders(GluuLdapConfiguration ldapAuthConfig) {
        Properties connectionProperties = prepareAuthConnectionProperties(ldapAuthConfig);
        LdapConnectionService connectionProvider = createConnectionProvider(connectionProperties);

        Properties bindConnectionProperties = prepareBindConnectionProperties(connectionProperties);
        LdapConnectionService bindConnectionProvider = createBindConnectionProvider(bindConnectionProperties, connectionProperties);
    	
        return new LdapConnectionProviders(connectionProvider, bindConnectionProvider);
    }

	private Properties prepareAuthConnectionProperties(GluuLdapConfiguration ldapAuthConfig) {
        FileConfiguration configuration = configurationFactory.getLdapConfiguration();

		Properties properties = (Properties) configuration.getProperties().clone();
		if (ldapAuthConfig != null) {
		    properties.setProperty("servers", buildServersString(ldapAuthConfig.getServers()));
		    
		    String bindDn = ldapAuthConfig.getBindDN();
		    if (StringHelper.isNotEmpty(bindDn)) {
		    	properties.setProperty("bindDN", bindDn);
				properties.setProperty("bindPassword", ldapAuthConfig.getBindPassword());
		    }
			properties.setProperty("useSSL", Boolean.toString(ldapAuthConfig.isUseSSL()));
			properties.setProperty("maxconnections", Integer.toString(ldapAuthConfig.getMaxConnections()));
		}

		return properties;
	}

    private Properties prepareBindConnectionProperties(Properties connectionProperties) {
		// TODO: Use own properties with prefix specified in variable 'bindConfigurationComponentName'
		Properties bindProperties = (Properties) connectionProperties.clone();
		bindProperties.remove("bindDN");
		bindProperties.remove("bindPassword");

		return bindProperties;
	}

	private LdapConnectionService createConnectionProvider(Properties connectionProperties) {
		EncryptionService securityService = encryptionServiceInstance.get();
		LdapConnectionService connectionProvider = new LdapConnectionService(securityService.decryptProperties(connectionProperties));

		return connectionProvider;
	}

	private LdapConnectionService createBindConnectionProvider(Properties bindConnectionProperties, Properties connectionProperties) {
		LdapConnectionService bindConnectionProvider = createConnectionProvider(bindConnectionProperties);
		if (ResultCode.INAPPROPRIATE_AUTHENTICATION.equals(bindConnectionProvider.getCreationResultCode())) {
			log.warn("It's not possible to create authentication LDAP connection pool using anonymous bind. Attempting to create it using binDN/bindPassword");
			bindConnectionProvider = createConnectionProvider(connectionProperties);
		}
		
		return bindConnectionProvider;
	}

	private String buildServersString(List<?> servers) {
		StringBuilder sb = new StringBuilder();

		if (servers == null) {
			return sb.toString();
		}
		
		boolean first = true;
		for (Object server : servers) {
			if (first) {
				first = false;
			} else {
				sb.append(",");
			}

			if (server instanceof SimpleProperty) {
				sb.append(((SimpleProperty) server).getValue());
			} else {
				sb.append(server);
			}
		}

		return sb.toString();
	}

	private List<oxIDPAuthConf> loadLdapIdpAuthConfigs(LdapEntryManager localLdapEntryManager) {
		GluuAppliance appliance = loadAppliance(localLdapEntryManager, "oxIDPAuthentication");

		if ((appliance == null) || (appliance.getOxIDPAuthentication() == null)) {
			return null;
		}

		List<oxIDPAuthConf> configurations = new ArrayList<oxIDPAuthConf>();
		for (String configurationJson : appliance.getOxIDPAuthentication()) {

			try {
				oxIDPAuthConf configuration = (oxIDPAuthConf) jsonToObject(configurationJson, oxIDPAuthConf.class);
				if (configuration.getType().equalsIgnoreCase("ldap") || configuration.getType().equalsIgnoreCase("auth")) {
					configurations.add(configuration);
				}
			} catch (Exception ex) {
				log.error("Failed to create object by json: '{}'", configurationJson, ex);
			}
		}

		return configurations;
	}

	private void setDefaultAuthenticationMethod(LdapEntryManager localLdapEntryManager) {
		String currentAuthMethod = null;
		if (this.authenticationMode != null) {
			currentAuthMethod = this.authenticationMode.getName();
		}

		String actualAuthMethod = getActualDefaultAuthenticationMethod(localLdapEntryManager);
		
		if (!StringHelper.equals(currentAuthMethod, actualAuthMethod)) {
			authenticationMode = null;
			if (actualAuthMethod != null) {
				this.authenticationMode = new AuthenticationMode(actualAuthMethod);
			}

			authenticationModeInstance.destroy(authenticationModeInstance.get());
		}
	}

	private String getActualDefaultAuthenticationMethod(LdapEntryManager localLdapEntryManager) {
		GluuAppliance appliance = loadAppliance(localLdapEntryManager, "oxAuthenticationMode");

		if (appliance == null) {
			return null;
		}

		return appliance.getAuthenticationMode();
	}
	
	@Produces @ApplicationScoped
	public AuthenticationMode getDefaultAuthenticationMode() {
		return authenticationMode;
	}

	private GluuAppliance loadAppliance(LdapEntryManager localLdapEntryManager, String ... ldapReturnAttributes) {
		String baseDn = configurationFactory.getBaseDn().getAppliance();
		String applianceInum = configurationFactory.getAppConfiguration().getApplianceInum();
		if (StringHelper.isEmpty(baseDn) || StringHelper.isEmpty(applianceInum)) {
			return null;
		}

		String applianceDn = String.format("inum=%s,%s", applianceInum, baseDn);

		GluuAppliance appliance = null;
		try {
			appliance = localLdapEntryManager.find(GluuAppliance.class, applianceDn, ldapReturnAttributes);
		} catch (LdapMappingException ex) {
			log.error("Failed to load appliance entry from Ldap", ex);
			return null;
		}

		return appliance;
	}

	public GluuLdapConfiguration loadLdapAuthConfig(oxIDPAuthConf configuration) {
		if (configuration == null) {
			return null;
		}

		try {
			if (configuration.getType().equalsIgnoreCase("auth")) {
				return mapLdapConfig(configuration.getConfig());
			}
		} catch (Exception ex) {
			log.error("Failed to create object by oxIDPAuthConf: '{}'", configuration, ex);
		}

		return null;
	}

	private List<GluuLdapConfiguration> loadLdapAuthConfigs(LdapEntryManager localLdapEntryManager) {
		List<GluuLdapConfiguration> ldapAuthConfigs = new ArrayList<GluuLdapConfiguration>();

		List<oxIDPAuthConf> ldapIdpAuthConfigs = loadLdapIdpAuthConfigs(localLdapEntryManager);
		if (ldapIdpAuthConfigs == null) {
			return ldapAuthConfigs;
		}

		for (oxIDPAuthConf ldapIdpAuthConfig : ldapIdpAuthConfigs) {
			GluuLdapConfiguration ldapAuthConfig = loadLdapAuthConfig(ldapIdpAuthConfig);
			if ((ldapAuthConfig != null) && ldapAuthConfig.isEnabled()) {
				ldapAuthConfigs.add(ldapAuthConfig);
			}
		}
		
		return ldapAuthConfigs; 
	}

	private GluuLdapConfiguration mapLdapConfig(String config) throws Exception {
		return (GluuLdapConfiguration) jsonToObject(config, GluuLdapConfiguration.class);
	}

	private Object jsonToObject(String json, Class<?> clazz) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Object clazzObject = mapper.readValue(json, clazz);

		return clazzObject;
	}

}