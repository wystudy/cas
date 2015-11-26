package org.jasig.cas.support.saml.services;

import com.fasterxml.jackson.annotation.JsonIgnore;
import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.jasig.cas.services.AbstractRegisteredService;
import org.jasig.cas.services.RegexRegisteredService;
import org.jasig.cas.services.RegisteredService;
import org.jasig.cas.support.saml.OpenSamlConfigBean;
import org.jasig.cas.util.ApplicationContextProvider;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.saml.common.SAMLException;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.metadata.resolver.ChainingMetadataResolver;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.DOMMetadataResolver;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.RoleDescriptor;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.saml2.metadata.SSODescriptor;
import org.opensaml.security.credential.Credential;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.PostConstruct;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link SamlRegisteredService} is responsible for managing the SAML metadata for a given SP.
 *
 * @author Misagh Moayyed
 * @since 4.2
 */
public final class SamlRegisteredService extends RegexRegisteredService {
    private static final long serialVersionUID = 1218757374062931021L;

    private List<String> supportedNameFormats = new ArrayList<>();

    private boolean signAssertions;

    @JsonIgnore
    private Credential signingCredential;

    @JsonIgnore
    private SSODescriptor ssoDescriptor;

    @JsonIgnore
    private Resource metadataLocationResource;

    private String metadataLocation;

    @JsonIgnore
    private transient ChainingMetadataResolver metadataResolver;;

    @JsonIgnore
    private final transient Object lock = new Object();

    /**
     * Instantiates a new Saml registered service.
     */
    public SamlRegisteredService() {
        super();
        this.supportedNameFormats.add(NameID.UNSPECIFIED);
        this.supportedNameFormats.add(NameID.TRANSIENT);
        this.supportedNameFormats.add(NameID.EMAIL);
    }

    /**
     * Init.
     */
    @PostConstruct
    public void init() {
        try {
            if (metadataLocation.startsWith("http")) {
                this.metadataLocationResource = new UrlResource(metadataLocation);
            } else if (metadataLocation.startsWith("classpath")) {
                this.metadataLocationResource = new ClassPathResource(metadataLocation);
            } else {
                this.metadataLocationResource = new FileSystemResource(metadataLocation);
            }
            if (!this.metadataLocationResource.exists() || !this.metadataLocationResource.isReadable()) {
                throw new FileNotFoundException("Resource does not exist or is unreadable");
            }
        } catch (final Exception e) {
            throw new IllegalArgumentException("Metadata location " + metadataLocation + " cannot be determined");
        }

        loadMetadataFromResource();
    }

    public void setMetadataLocation(final String metadataLocation) {
        this.metadataLocation = metadataLocation;
    }

    public void setSupportedNameFormats(final List<String> supportedNameFormats) {
        this.supportedNameFormats = supportedNameFormats;
    }

    public boolean isSignAssertions() {
        return signAssertions;
    }

    public void setSignAssertions(final boolean signAssertions) {
        this.signAssertions = signAssertions;
    }

    public List<String> getSupportedNameFormats() {
        return this.supportedNameFormats;
    }

    public Credential getSigningCredential() {
        return signingCredential;
    }


    /**
     * Gets sso descriptor.
     *
     * @return the sso descriptor
     */
    public RoleDescriptor getSsoDescriptor() {
        try {
            final CriteriaSet criterions = new CriteriaSet(new EntityIdCriterion(getServiceId()));
            final EntityDescriptor entityDescriptor = this.metadataResolver.resolveSingle(criterions);
            if (entityDescriptor == null) {
                throw new SAMLException("Cannot find entity " + getServiceId() + " in metadata provider");
            }
            final List<RoleDescriptor> list = entityDescriptor.getRoleDescriptors(SPSSODescriptor.DEFAULT_ELEMENT_NAME,
                    SAMLConstants.SAML20P_NS);
            final RoleDescriptor roleDescriptor = list != null && !list.isEmpty() ? list.get(0) : null;
            return roleDescriptor;

        } catch (final Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void loadMetadataFromResource() {

        final OpenSamlConfigBean configBean = ApplicationContextProvider.getApplicationContext().getBean(OpenSamlConfigBean.class);
        try (final InputStream in = this.metadataLocationResource.getInputStream()) {
            logger.debug("Parsing [{}]", this.metadataLocationResource.getFilename());
            final Document document = configBean.getParserPool().parse(in);

            final List<MetadataResolver> resolvers = buildSingleMetadataResolver(document);
            this.metadataResolver = new ChainingMetadataResolver();
            synchronized (this.lock) {
                this.metadataResolver.setId(ChainingMetadataResolver.class.getCanonicalName());
                this.metadataResolver.setResolvers(resolvers);
                this.metadataResolver.initialize();

            }
        } catch (final Exception e) {
            logger.warn("Could not retrieve input stream from resource. Moving on...", e);
        }
    }

    private List<MetadataResolver> buildSingleMetadataResolver(final Document document) throws IOException {
        final OpenSamlConfigBean configBean = ApplicationContextProvider.getApplicationContext().getBean(OpenSamlConfigBean.class);

        final List<MetadataResolver> resolvers = new ArrayList<>();
        final Element metadataRoot = document.getDocumentElement();
        final DOMMetadataResolver metadataProvider = new DOMMetadataResolver(metadataRoot);

        metadataProvider.setParserPool(configBean.getParserPool());
        metadataProvider.setFailFastInitialization(true);
        metadataProvider.setRequireValidMetadata(true);
        metadataProvider.setId(metadataProvider.getClass().getCanonicalName());
        logger.debug("Initializing metadata resolver for [{}]", this.metadataLocationResource.getURL());

        try {
            metadataProvider.initialize();
        } catch (final ComponentInitializationException ex) {
            logger.warn("Could not initialize metadata resolver. Resource will be ignored", ex);
        }
        resolvers.add(metadataProvider);
        return resolvers;
    }

    @Override
    public void copyFrom(final RegisteredService source) {
        super.copyFrom(source);
        final SamlRegisteredService samlRegisteredService = (SamlRegisteredService) source;
        setSignAssertions(samlRegisteredService.isSignAssertions());
        setSupportedNameFormats(samlRegisteredService.getSupportedNameFormats());
    }

    @Override
    protected AbstractRegisteredService newInstance() {
        return new SamlRegisteredService();
    }



    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        final SamlRegisteredService rhs = (SamlRegisteredService) obj;
        return new EqualsBuilder()
                .appendSuper(super.equals(obj))
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(13, 17)
                .appendSuper(super.hashCode())
                .toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .appendSuper(super.toString())
                .append("supportedNameFormats", supportedNameFormats)
                .append("signAssertions", signAssertions)
                .append("metadataLocation", metadataLocation)
                .toString();
    }
}
