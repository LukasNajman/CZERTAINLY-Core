package com.czertainly.core.service.impl;

import com.czertainly.api.exception.AlreadyExistException;
import com.czertainly.api.exception.ConnectorException;
import com.czertainly.api.exception.NotFoundException;
import com.czertainly.api.exception.ValidationError;
import com.czertainly.api.exception.ValidationException;
import com.czertainly.api.model.client.certificate.*;
import com.czertainly.api.model.client.certificate.owner.CertificateOwnerBulkUpdateDto;
import com.czertainly.api.model.client.certificate.owner.CertificateOwnerRequestDto;
import com.czertainly.api.model.core.audit.ObjectType;
import com.czertainly.api.model.core.audit.OperationType;
import com.czertainly.api.model.core.certificate.CertificateComplianceResultDto;
import com.czertainly.api.model.core.certificate.CertificateComplianceStorageDto;
import com.czertainly.api.model.core.certificate.CertificateDto;
import com.czertainly.api.model.core.certificate.CertificateEvent;
import com.czertainly.api.model.core.certificate.CertificateEventStatus;
import com.czertainly.api.model.core.certificate.CertificateStatus;
import com.czertainly.api.model.core.certificate.CertificateType;
import com.czertainly.api.model.core.compliance.ComplianceRuleStatus;
import com.czertainly.api.model.core.compliance.ComplianceStatus;
import com.czertainly.api.model.core.location.LocationDto;
import com.czertainly.api.model.core.search.DynamicSearchInternalResponse;
import com.czertainly.api.model.core.search.SearchFieldDataDto;
import com.czertainly.api.model.core.search.SearchLabelConstants;
import com.czertainly.core.aop.AuditLogged;
import com.czertainly.core.dao.entity.*;
import com.czertainly.core.dao.repository.AdminRepository;
import com.czertainly.core.dao.repository.CertificateContentRepository;
import com.czertainly.core.dao.repository.CertificateRepository;
import com.czertainly.core.dao.repository.ClientRepository;
import com.czertainly.core.dao.repository.DiscoveryCertificateRepository;
import com.czertainly.core.dao.repository.GroupRepository;
import com.czertainly.core.dao.repository.RaProfileRepository;
import com.czertainly.core.service.CertValidationService;
import com.czertainly.core.service.CertificateEventHistoryService;
import com.czertainly.core.service.CertificateService;
import com.czertainly.core.service.ComplianceService;
import com.czertainly.core.service.LocationService;
import com.czertainly.core.service.SearchService;
import com.czertainly.core.util.CertificateUtil;
import com.czertainly.core.util.MetaDefinitions;
import com.czertainly.core.util.OcspUtil;
import com.czertainly.core.util.X509ObjectToString;
import com.google.common.collect.Lists;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
@Secured({"ROLE_ADMINISTRATOR", "ROLE_SUPERADMINISTRATOR", "ROLE_CLIENT", "ROLE_ACME"})
public class CertificateServiceImpl implements CertificateService {

    // Default page size for the certificate search API when page size is not provided
    public static final Integer DEFAULT_PAGE_SIZE = 10;
    // Maximum page size for search API operation
    public static final Integer MAX_PAGE_SIZE = 1000;
    // Default batch size to perform bulk delete operation on Certificates
    public static final Integer DELETE_BATCH_SIZE = 1000;
    private static final Logger logger = LoggerFactory.getLogger(CertificateServiceImpl.class);

    @Autowired
    private CertificateRepository certificateRepository;

    @Autowired
    private RaProfileRepository raProfileRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private CertificateContentRepository certificateContentRepository;

    @Autowired
    private DiscoveryCertificateRepository discoveryCertificateRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private CertValidationService certValidationService;

    @Autowired
    private CertificateEventHistoryService certificateEventHistoryService;

    @Autowired
    private SearchService searchService;

    @Lazy
    @Autowired
    private LocationService locationService;


    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.REQUEST)
    public CertificateResponseDto listCertificates(SearchRequestDto request) throws ValidationException {
        return getCertificatesWithFilter(request);

    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.REQUEST)
    public CertificateDto getCertificate(String uuid) throws NotFoundException {
        Certificate entity = getCertificateEntity(uuid);
        CertificateDto dto = entity.mapToDto();
        if (entity.getComplianceResult() != null) {
            dto.setNonCompliantRules(frameComplianceResult(entity.getComplianceResult()));
        } else {
            dto.setComplianceStatus(ComplianceStatus.NA);
        }
        return dto;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.REQUEST)
    public Certificate getCertificateEntity(String uuid) throws NotFoundException {
        return certificateRepository.findByUuid(uuid).orElseThrow(() -> new NotFoundException(Certificate.class, uuid));
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.REQUEST)
    public Certificate getCertificateEntityByContent(String content) {
        CertificateContent certificateContent = certificateContentRepository.findByContent(content);
        return certificateRepository.findByCertificateContent(certificateContent);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.REQUEST)
    public Certificate getCertificateEntityBySerial(String serialNumber) throws NotFoundException {
        return certificateRepository.findBySerialNumberIgnoreCase(serialNumber)
                .orElseThrow(() -> new NotFoundException(Certificate.class, serialNumber));
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.DELETE)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void removeCertificate(String uuid) throws NotFoundException {
        Certificate certificate = certificateRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(Certificate.class, uuid));

        List<ValidationError> errors = new ArrayList<>();

        for (Client client : clientRepository.findByCertificate(certificate)) {
            errors.add(ValidationError.create("Certificate has Client " + client.getName() + " associated to it"));
            certificateEventHistoryService.addEventHistory(CertificateEvent.DELETE, CertificateEventStatus.FAILED, "Associated to Client " + client.getName(), "", certificate);
        }

        for (Admin admin : adminRepository.findByCertificate(certificate)) {
            errors.add(ValidationError.create("Certificate has Admin " + admin.getName() + " associated to it"));
            certificateEventHistoryService.addEventHistory(CertificateEvent.DELETE, CertificateEventStatus.FAILED, "Associated to Admin  " + admin.getName(), "", certificate);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Could not delete certificate", errors);
        }

        // remove certificate from Locations
        try {
            locationService.removeCertificateFromLocations(uuid);
        } catch (ConnectorException e) {
            logger.error("Failed to remove Certificate {} from Locations", uuid);
        }

        if (discoveryCertificateRepository.findByCertificateContent(certificate.getCertificateContent()).isEmpty()) {
            CertificateContent content = certificateContentRepository
                    .findById(certificate.getCertificateContent().getId()).orElse(null);
            if (content != null) {
                certificateRepository.delete(certificate);
                certificateContentRepository.delete(content);
            }
        } else {
            certificateRepository.delete(certificate);
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CHANGE)
    public void updateRaProfile(String uuid, CertificateUpdateRAProfileDto request) throws NotFoundException {
        Certificate certificate = certificateRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(Certificate.class, uuid));
        RaProfile raProfile = raProfileRepository.findByUuid(request.getRaProfileUuid())
                .orElseThrow(() -> new NotFoundException(RaProfile.class, request.getRaProfileUuid()));
        String originalProfile = "undefined";
        if (certificate.getRaProfile() != null) {
            originalProfile = certificate.getRaProfile().getName();
        }
        certificate.setRaProfile(raProfile);
        certificateRepository.save(certificate);
        try {
            complianceService.checkComplianceOfCertificate(certificate);
        } catch (ConnectorException e) {
            logger.error("Error when checking compliance:", e);
        }
        certificateEventHistoryService.addEventHistory(CertificateEvent.UPDATE_RA_PROFILE, CertificateEventStatus.SUCCESS, originalProfile + " -> " + raProfile.getName(), "", certificate);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CHANGE)
    public void updateCertificateGroup(String uuid, CertificateUpdateGroupDto request) throws NotFoundException {
        Certificate certificate = certificateRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(Certificate.class, uuid));

        CertificateGroup certificateGroup = groupRepository.findByUuid(request.getGroupUuid())
                .orElseThrow(() -> new NotFoundException(CertificateGroup.class, request.getGroupUuid()));
        String originalGroup = "undefined";
        if (certificate.getGroup() != null) {
            originalGroup = certificate.getGroup().getName();
        }
        certificate.setGroup(certificateGroup);
        certificateRepository.save(certificate);
        certificateEventHistoryService.addEventHistory(CertificateEvent.UPDATE_GROUP, CertificateEventStatus.SUCCESS, originalGroup + " -> " + certificateGroup.getName(), "", certificate);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CHANGE)
    public void updateOwner(String uuid, CertificateOwnerRequestDto request) throws NotFoundException {
        Certificate certificate = certificateRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(Certificate.class, uuid));
        String originalOwner = certificate.getOwner();
        if (originalOwner == null || originalOwner.isEmpty()) {
            originalOwner = "undefined";
        }
        certificate.setOwner(request.getOwner());
        certificateRepository.save(certificate);
        certificateEventHistoryService.addEventHistory(CertificateEvent.UPDATE_OWNER, CertificateEventStatus.SUCCESS, originalOwner + " -> " + request.getOwner(), "", certificate);
    }

    @Async
    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CHANGE)
    public void bulkUpdateRaProfile(MultipleRAProfileUpdateDto request) throws NotFoundException {
        List<CertificateEventHistory> batchHistoryOperationList = new ArrayList<>();
        RaProfile raProfile = raProfileRepository.findByUuid(request.getUuid())
                .orElseThrow(() -> new NotFoundException(RaProfile.class, request.getUuid()));
        if (request.getFilters() == null) {
            List<Certificate> batchOperationList = new ArrayList<>();
            for (String certificateUuid : request.getCertificateUuids()) {
                Certificate certificate = certificateRepository.findByUuid(certificateUuid)
                        .orElseThrow(() -> new NotFoundException(Certificate.class, certificateUuid));
                batchHistoryOperationList.add(certificateEventHistoryService.getEventHistory(CertificateEvent.UPDATE_RA_PROFILE, CertificateEventStatus.SUCCESS, certificate.getRaProfile() != null ? certificate.getRaProfile().getName() : "undefined" + " -> " + raProfile.getName(), "", certificate));
                certificate.setRaProfile(raProfile);
                batchOperationList.add(certificate);
            }
            certificateRepository.saveAll(batchOperationList);
            certificateEventHistoryService.asyncSaveAllInBatch(batchHistoryOperationList);
        } else {
            String profileUpdateQuery = "UPDATE Certificate c SET c.raProfile = " + raProfile.getId() + searchService.getCompleteSearchQuery(request.getFilters(), "certificate", "", getSearchableFieldInformation(), true, false).replace("GROUP BY c.id ORDER BY c.id DESC", "");
            certificateRepository.bulkUpdateQuery(profileUpdateQuery);
            certificateEventHistoryService.addEventHistoryForRequest(request.getFilters(), "Certificate", getSearchableFieldInformation(), CertificateEvent.UPDATE_RA_PROFILE, CertificateEventStatus.SUCCESS, "RA Profile Name: " + raProfile.getName());
            bulkUpdateRaProfileComplianceCheck(request.getFilters());
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CHANGE)
    public void bulkUpdateCertificateGroup(MultipleGroupUpdateDto request) throws NotFoundException {
        CertificateGroup certificateGroup = groupRepository.findByUuid(request.getUuid())
                .orElseThrow(() -> new NotFoundException(CertificateGroup.class, request.getUuid()));
        List<CertificateEventHistory> batchHistoryOperationList = new ArrayList<>();
        if (request.getFilters() == null) {
            List<Certificate> batchOperationList = new ArrayList<>();

            for (String certificateUuid : request.getCertificateUuids()) {
                Certificate certificate = certificateRepository.findByUuid(certificateUuid)
                        .orElseThrow(() -> new NotFoundException(Certificate.class, certificateUuid));
                batchHistoryOperationList.add(certificateEventHistoryService.getEventHistory(CertificateEvent.UPDATE_GROUP, CertificateEventStatus.SUCCESS, certificate.getGroup() != null ? certificate.getGroup().getName() : "undefined" + " -> " + certificateGroup.getName(), "", certificate));
                certificate.setGroup(certificateGroup);
                batchOperationList.add(certificate);
            }
            certificateRepository.saveAll(batchOperationList);
            certificateEventHistoryService.asyncSaveAllInBatch(batchHistoryOperationList);
        } else {
            String groupUpdateQuery = "UPDATE Certificate c SET c.group = " + certificateGroup.getId() + searchService.getCompleteSearchQuery(request.getFilters(), "certificate", "", getSearchableFieldInformation(), true, false).replace("GROUP BY c.id ORDER BY c.id DESC", "");
            certificateRepository.bulkUpdateQuery(groupUpdateQuery);
            certificateEventHistoryService.addEventHistoryForRequest(request.getFilters(), "Certificate", getSearchableFieldInformation(), CertificateEvent.UPDATE_GROUP, CertificateEventStatus.SUCCESS, "Group Name: " + certificateGroup.getName());
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CHANGE)
    public void bulkUpdateOwner(CertificateOwnerBulkUpdateDto request) throws NotFoundException {
        List<CertificateEventHistory> batchHistoryOperationList = new ArrayList<>();
        if (request.getFilters() == null) {
            List<Certificate> batchOperationList = new ArrayList<>();
            for (String certificateUuid : request.getCertificateUuids()) {
                Certificate certificate = certificateRepository.findByUuid(certificateUuid)
                        .orElseThrow(() -> new NotFoundException(Certificate.class, certificateUuid));
                batchHistoryOperationList.add(certificateEventHistoryService.getEventHistory(CertificateEvent.UPDATE_OWNER, CertificateEventStatus.SUCCESS, certificate.getOwner() + " -> " + request.getOwner(), "", certificate));
                certificate.setOwner(request.getOwner());
                batchOperationList.add(certificate);
            }
            certificateRepository.saveAll(batchOperationList);
            certificateEventHistoryService.asyncSaveAllInBatch(batchHistoryOperationList);
        } else {
            String ownerUpdateQuery = "UPDATE Certificate c SET c.owner = '" + request.getOwner() + "' " + searchService.getCompleteSearchQuery(request.getFilters(), "certificate", "", getSearchableFieldInformation(), true, false).replace("GROUP BY c.id ORDER BY c.id DESC", "");
            certificateRepository.bulkUpdateQuery(ownerUpdateQuery);
            certificateEventHistoryService.addEventHistoryForRequest(request.getFilters(), "Certificate", getSearchableFieldInformation(), CertificateEvent.UPDATE_OWNER, CertificateEventStatus.SUCCESS, "Owner: " + request.getOwner());
        }

    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.DELETE)
    @Async("threadPoolTaskExecutor")
    public void bulkRemoveCertificate(RemoveCertificateDto request) throws NotFoundException {
        List<String> failedDeleteCerts = new ArrayList<>();
        Integer totalItems;
        BulkOperationResponse bulkOperationResponse = new BulkOperationResponse();
        List<CertificateEventHistory> batchHistoryOperationList = new ArrayList<>();
        if (request.getFilters() == null) {
            for (String uuid : request.getUuids()) {
                Certificate certificate = certificateRepository.findByUuid(uuid)
                        .orElseThrow(() -> new NotFoundException(Certificate.class, uuid));
                if (!adminRepository.findByCertificate(certificate).isEmpty()) {
                    logger.warn("Certificate tagged as admin. Unable to delete certificate with common name {}", certificate.getCommonName());
                    batchHistoryOperationList.add(certificateEventHistoryService.getEventHistory(CertificateEvent.DELETE, CertificateEventStatus.FAILED, "Associated to Client ", "", certificate));
                    failedDeleteCerts.add(certificate.getUuid());
                    continue;
                }

                if (!clientRepository.findByCertificate(certificate).isEmpty()) {
                    logger.warn("Certificate tagged as client. Unable to delete certificate with common name {}", certificate.getCommonName());
                    batchHistoryOperationList.add(certificateEventHistoryService.getEventHistory(CertificateEvent.DELETE, CertificateEventStatus.FAILED, "Associated to Admin ", "", certificate));
                    failedDeleteCerts.add(certificate.getUuid());
                    continue;
                }

                if (discoveryCertificateRepository.findByCertificateContent(certificate.getCertificateContent()).isEmpty()) {
                    certificateContentRepository
                            .findById(certificate.getCertificateContent().getId()).ifPresent(content -> certificateContentRepository.delete(content));
                }

                certificateRepository.delete(certificate);
            }
        } else {
            List<Certificate> certList = (List<Certificate>) searchService.completeSearchQueryExecutor(request.getFilters(), "Certificate", getSearchableFieldInformation());
            totalItems = certList.size();

            String joins = " LEFT JOIN Admin t1 ON c.id = t1.id  LEFT JOIN Client t2 ON c.id = t2.id WHERE t1.id IS NULL and t2.id is NULL";
            String clientJoins = " LEFT JOIN Client t1 ON c.id = t1.id LEFT JOIN Admin t2 ON c.id = t2.id WHERE t2.id IS NOT NULL AND t1.id IS NOT NULL ";

            String customQuery = searchService.getQueryDynamicBasedOnFilter(request.getFilters(), "Certificate", getSearchableFieldInformation(), joins, false, false);
            String clientCustomQuery = searchService.getQueryDynamicBasedOnFilter(request.getFilters(), "Certificate", getSearchableFieldInformation(), clientJoins, false, false);

            List<Certificate> clientUsedCertificates = (List<Certificate>) searchService.customQueryExecutor(clientCustomQuery);
            List<Certificate> certListDyn = (List<Certificate>) searchService.customQueryExecutor(customQuery);

            bulkOperationResponse.setFailedItem(Long.valueOf(totalItems - certListDyn.size()));

            for (Certificate certificate : clientUsedCertificates) {
                batchHistoryOperationList.add(certificateEventHistoryService.getEventHistory(CertificateEvent.DELETE, CertificateEventStatus.FAILED, "Associated to Admin / Client ", "", certificate));
            }
            for (List<Certificate> certificates : Lists.partition(certListDyn, DELETE_BATCH_SIZE)) {
                certificateRepository.deleteAll(certificates);
            }
            for (List<CertificateContent> certificateContents : Lists.partition(certificateContentRepository.findCertificateContentNotUsed(), DELETE_BATCH_SIZE)) {
                certificateContentRepository.deleteAll(certificateContents);
            }
        }
        certificateEventHistoryService.asyncSaveAllInBatch(batchHistoryOperationList);
    }

    @Override
    public List<SearchFieldDataDto> getSearchableFieldInformation() {
        return getSearchableFieldsMap();
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CHANGE)
    public void updateIssuer() {
        for (Certificate certificate : certificateRepository.findAllByIssuerSerialNumber(null)) {
            if (!certificate.getIssuerDn().equals(certificate.getSubjectDn())) {
                for (Certificate issuer : certificateRepository.findBySubjectDn(certificate.getIssuerDn())) {
                    X509Certificate subCert;
                    X509Certificate issCert;
                    try {
                        subCert = getX509(certificate.getCertificateContent().getContent());
                        issCert = getX509(issuer.getCertificateContent().getContent());
                    } catch (Exception e) {
                        continue;
                    }

                    if (verifySignature(subCert, issCert)) {
                        try {
                            X509Certificate issuerCert = CertificateUtil
                                    .parseCertificate(issuer.getCertificateContent().getContent());
                            X509Certificate subjectCert = CertificateUtil
                                    .parseCertificate(certificate.getCertificateContent().getContent());

                            try {
                                subjectCert.verify(issuerCert.getPublicKey());
                                certificate.setIssuerSerialNumber(issuer.getSerialNumber());
                                certificateRepository.save(certificate);
                            } catch (Exception e) {
                                logger.debug("Error when getting the issuer");
                            }

                        } catch (CertificateException e) {
                            logger.warn("Unable to parse the issuer with subject {}", certificate.getIssuerDn());
                        }
                    }
                }
            }
        }
    }

    private boolean verifySignature(X509Certificate subjectCertificate, X509Certificate issuerCertificate) {
        try {
            subjectCertificate.verify(issuerCertificate.getPublicKey());
            return true;
        } catch (Exception e) {
            logger.warn("Unable to verify certificate for signature.", e);
            return false;
        }
    }

    private X509Certificate getX509(String certificate) throws CertificateException {
        return CertificateUtil.getX509Certificate(certificate.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("\r", "").replace("\n", "").replace("-----END CERTIFICATE-----", ""));
    }

    @Override
    public Certificate createCertificate(String certificateData, CertificateType certificateType) throws com.czertainly.api.exception.CertificateException {
        Certificate entity = new Certificate();
        String fingerprint;

        // by default we are working with the X.509 certificate
        if (certificateType == null) {
            certificateType = CertificateType.X509;
        }
        if (!certificateType.equals(CertificateType.X509)) {
            String message = "Unsupported type of the certificate: " + certificateType;
            logger.debug(message);
            throw new com.czertainly.api.exception.CertificateException(message);
        } else {
            X509Certificate certificate;
            try {
                certificate = getX509(certificateData);
            } catch (CertificateException e) {
                String message = "Failed to get parse the certificate " + certificateData + " > " + e.getMessage();
                logger.error("message");
                throw new com.czertainly.api.exception.CertificateException(message);
            }
            try {
                fingerprint = CertificateUtil.getThumbprint(certificate.getEncoded());
                Optional<Certificate> existingCertificate = certificateRepository.findByFingerprint(fingerprint);

                if (existingCertificate.isPresent()) {
                    logger.debug("Returning existing certificate with fingerprint {}", fingerprint);
                    return existingCertificate.get();
                }
            } catch (NoSuchAlgorithmException | CertificateException e) {
                String message = "Failed to get thumbprint for certificate " + certificate.getSerialNumber() + " > " + e.getMessage();
                logger.error("message");
                throw new com.czertainly.api.exception.CertificateException(message);
            }

            CertificateUtil.prepareCertificate(entity, certificate);
            entity.setFingerprint(fingerprint);
            entity.setCertificateContent(checkAddCertificateContent(fingerprint, X509ObjectToString.toPem(certificate)));

            try {
                downloadUploadChain(entity);
                certValidationService.validate(entity);
            } catch (Exception e) {
                logger.warn("Unable to validate certificate {}, {}", entity.getUuid(), e.getMessage());
            }

            certificateRepository.save(entity);
            certificateComplianceCheck(entity);
            certificateEventHistoryService.addEventHistory(CertificateEvent.UPLOAD, CertificateEventStatus.SUCCESS, "Certificate uploaded", "", entity);

            return entity;
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CREATE)
    public Certificate createCertificateEntity(X509Certificate certificate) {
        logger.debug("Making a new entry for a certificate");
        Certificate modal = new Certificate();
        String fingerprint = null;
        try {
            fingerprint = CertificateUtil.getThumbprint(certificate.getEncoded());
            Optional<Certificate> existingCertificate = certificateRepository.findByFingerprint(fingerprint);

            if (existingCertificate.isPresent()) {
                return existingCertificate.get();
            }
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            logger.error("Unable to calculate sha 256 thumbprint");
        }

        CertificateUtil.prepareCertificate(modal, certificate);
        modal.setFingerprint(fingerprint);
        modal.setCertificateContent(checkAddCertificateContent(fingerprint, X509ObjectToString.toPem(certificate)));

        return modal;
    }

    private CertificateContent checkAddCertificateContent(String fingerprint, String content) {
        CertificateContent certificateContent = certificateContentRepository.findByFingerprint(fingerprint);
        if (certificateContent != null) {
            return certificateContent;
        }

        certificateContent = new CertificateContent();
        certificateContent.setContent(CertificateUtil.normalizeCertificateContent(content));
        certificateContent.setFingerprint(fingerprint);

        certificateContentRepository.save(certificateContent);
        return certificateContent;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CERTIFICATE, operation = OperationType.CREATE)
    public CertificateDto upload(UploadCertificateRequestDto request)
            throws AlreadyExistException, CertificateException {
        X509Certificate certificate = CertificateUtil.parseCertificate(request.getCertificate());
        String certificateSerialNumber = certificate.getSerialNumber().toString(16);
        if (certificateRepository.findBySerialNumberIgnoreCase(certificateSerialNumber).isPresent()) {
            throw new AlreadyExistException("Certificate already exists with serial number " + certificateSerialNumber);
        }
        Certificate entity = createCertificateEntity(certificate);
        certificateRepository.save(entity);
        try {
            downloadUploadChain(entity);
            certValidationService.validate(entity);
        } catch (Exception e) {
            logger.warn("Unable to validate the uploaded certificate, {}", e.getMessage());
        }
        certificateEventHistoryService.addEventHistory(CertificateEvent.UPLOAD, CertificateEventStatus.SUCCESS, "Certificate uploaded", "", entity);
        return entity.mapToDto();
    }

    @Override
    public Certificate checkCreateCertificate(String certificate) throws AlreadyExistException, CertificateException {
        X509Certificate x509Cert = CertificateUtil.parseCertificate(certificate);
        String certificateSerialNumber = x509Cert.getSerialNumber().toString(16);
        if (certificateRepository.findBySerialNumberIgnoreCase(certificateSerialNumber).isPresent()) {
            throw new AlreadyExistException("Certificate already exists with serial number " + certificateSerialNumber);
        }
        Certificate entity = createCertificateEntity(x509Cert);
        certificateRepository.save(entity);
        certificateComplianceCheck(entity);
        return entity;
    }

    @Override
    public Certificate checkCreateCertificateWithMeta(String certificate, String meta) throws AlreadyExistException, CertificateException {
        X509Certificate x509Cert = CertificateUtil.parseCertificate(certificate);
        String certificateSerialNumber = x509Cert.getSerialNumber().toString(16);
        if (certificateRepository.findBySerialNumberIgnoreCase(certificateSerialNumber).isPresent()) {
            throw new AlreadyExistException("Certificate already exists with serial number " + certificateSerialNumber);
        }
        Certificate entity = createCertificateEntity(x509Cert);
        entity.setMeta(meta);
        certificateRepository.save(entity);
        certificateComplianceCheck(entity);
        return entity;
    }

    @Override
    public void revokeCertificate(String serialNumber) {
        try {
            Certificate certificate = certificateRepository.findBySerialNumberIgnoreCase(serialNumber).orElseThrow(() -> new NotFoundException(Certificate.class, serialNumber));
            certificate.setStatus(CertificateStatus.REVOKED);
            certificateRepository.save(certificate);
        } catch (NotFoundException e) {
            logger.warn("Unable to find the certificate with serialNumber {}", serialNumber);
        }
    }

    @Override
    public List<LocationDto> listLocations(String certificateUuid) throws NotFoundException {
        Certificate certificateEntity = certificateRepository.findByUuid(certificateUuid)
                .orElseThrow(() -> new NotFoundException(Certificate.class, certificateUuid));
        return certificateEntity.getLocations().stream()
                .map(CertificateLocation::getLocation)
                .map(Location::mapToDtoSimple)
                .collect(Collectors.toList());
    }


    @Override
    public List<Certificate> listCertificatesForRaProfile(RaProfile raProfile) {
        return certificateRepository.findByRaProfile(raProfile);
    }

    @Override
    @Async
    public void checkCompliance(CertificateComplianceCheckDto request) {
        for (String uuid : request.getCertificateUuids()) {
            try {
                complianceService.checkComplianceOfCertificate(getCertificateEntity(uuid));
            } catch (ConnectorException e) {
                logger.error("Compliance check failed.", e);
            }
        }
    }

    @Override
    public void updateCertificateEntity(Certificate certificate) {
        certificateRepository.save(certificate);
    }


    private List<SearchFieldDataDto> getSearchableFieldsMap() {

        SearchFieldDataDto raProfileFilter = SearchLabelConstants.RA_PROFILE_NAME_FILTER;
        raProfileFilter.setValue(raProfileRepository.findAll().stream().map(RaProfile::getName).collect(Collectors.toList()));

        SearchFieldDataDto groupFilter = SearchLabelConstants.GROUP_NAME_FILTER;
        groupFilter.setValue(groupRepository.findAll().stream().map(CertificateGroup::getName).collect(Collectors.toList()));

        SearchFieldDataDto signatureAlgorithmFilter = SearchLabelConstants.SIGNATURE_ALGORITHM_FILTER;
        signatureAlgorithmFilter.setValue(new ArrayList<>(certificateRepository.findDistinctSignatureAlgorithm()));

        SearchFieldDataDto publicKeyFilter = SearchLabelConstants.PUBLIC_KEY_ALGORITHM_FILTER;
        publicKeyFilter.setValue(new ArrayList<>(certificateRepository.findDistinctPublicKeyAlgorithm()));

        SearchFieldDataDto keySizeFilter = SearchLabelConstants.KEY_SIZE_FILTER;
        keySizeFilter.setValue(new ArrayList<>(certificateRepository.findDistinctKeySize()));

        SearchFieldDataDto keyUsageFilter = SearchLabelConstants.KEY_USAGE_FILTER;
        keyUsageFilter.setValue(serializedListOfStringToListOfObject(certificateRepository.findDistinctKeyUsage()));

        List<SearchFieldDataDto> fields = List.of(
                SearchLabelConstants.COMMON_NAME_FILTER,
                SearchLabelConstants.SERIAL_NUMBER_FILTER,
                SearchLabelConstants.ISSUER_SERIAL_NUMBER_FILTER,
                raProfileFilter,
                groupFilter,
                SearchLabelConstants.OWNER_FILTER,
                SearchLabelConstants.STATUS_FILTER,
                SearchLabelConstants.COMPLIANCE_STATUS_FILTER,
                SearchLabelConstants.ISSUER_COMMON_NAME_FILTER,
                SearchLabelConstants.FINGERPRINT_FILTER,
                signatureAlgorithmFilter,
                SearchLabelConstants.NOT_AFTER_FILTER,
                SearchLabelConstants.NOT_BEFORE_FILTER,
                SearchLabelConstants.SUBJECTDN_FILTER,
                SearchLabelConstants.ISSUERDN_FILTER,
                SearchLabelConstants.META_FILTER,
                SearchLabelConstants.SUBJECT_ALTERNATIVE_NAMES_FILTER,
                SearchLabelConstants.OCSP_VALIDATION_FILTER,
                SearchLabelConstants.CRL_VALIDATION_FILTER,
                SearchLabelConstants.SIGNATURE_VALIDATION_FILTER,
                publicKeyFilter,
                keySizeFilter,
                keyUsageFilter
        );
        logger.debug("Searchable Fields: {}", fields);
        return fields;
    }

    private List<Object> serializedListOfStringToListOfObject(List<String> serializedData) {
        Set<String> serSet = new LinkedHashSet<>();
        for (String obj : serializedData) {
            serSet.addAll(MetaDefinitions.deserializeArrayString(obj));
        }
        return new ArrayList<>(serSet);
    }

    private CertificateResponseDto getCertificatesWithFilter(SearchRequestDto request) {
        logger.debug("Certificate search request: {}", request.toString());
        CertificateResponseDto certificateResponseDto = new CertificateResponseDto();
        if (request.getItemsPerPage() == null) {
            request.setItemsPerPage(DEFAULT_PAGE_SIZE);
        }
        if (request.getPageNumber() == null) {
            request.setPageNumber(1);
        }
        if (request.getFilters() == null || request.getFilters().isEmpty()) {
            Pageable p = PageRequest.of(request.getPageNumber() - 1, request.getItemsPerPage());
            certificateResponseDto.setTotalPages((int) Math.ceil((double) certificateRepository.count() / request.getItemsPerPage()));
            certificateResponseDto.setTotalItems(certificateRepository.count());
            certificateResponseDto.setCertificates(certificateRepository.findAllByOrderByIdDesc(p).stream().map(Certificate::mapToDto).collect(Collectors.toList()));
        } else {
            DynamicSearchInternalResponse dynamicSearchInternalResponse = searchService.dynamicSearchQueryExecutor(request, "Certificate", getSearchableFieldInformation());
            certificateResponseDto.setItemsPerPage(request.getItemsPerPage());
            certificateResponseDto.setTotalItems(dynamicSearchInternalResponse.getTotalItems());
            certificateResponseDto.setTotalPages(dynamicSearchInternalResponse.getTotalPages());
            certificateResponseDto.setPageNumber(request.getPageNumber());
            certificateResponseDto.setCertificates(((List<Certificate>) dynamicSearchInternalResponse.getResult()).stream().map(Certificate::mapToDto).collect(Collectors.toList()));
        }
        return certificateResponseDto;
    }

    private List<CertificateComplianceResultDto> frameComplianceResult(CertificateComplianceStorageDto storageDto) {
        logger.debug("Framing Compliance Result from stored data: {}", storageDto);
        List<CertificateComplianceResultDto> result = new ArrayList<>();
        List<ComplianceRule> rules = complianceService.getComplianceRuleEntityForIds(storageDto.getNok());
        List<ComplianceRule> naRules = complianceService.getComplianceRuleEntityForIds(storageDto.getNa());
        for (ComplianceRule complianceRule : rules) {
            result.add(getCertificateComplianceResultDto(complianceRule, ComplianceRuleStatus.NOK));
        }
        // NA Rules are not required to be displayed in the UI
        // for (ComplianceRule complianceRule : naRules) {
        //     result.add(getCertificateComplianceResultDto(complianceRule, ComplianceRuleStatus.NA));
        // }
        logger.debug("Compliance Result: {}", result);
        return result;
    }

    private CertificateComplianceResultDto getCertificateComplianceResultDto(ComplianceRule rule, ComplianceRuleStatus status) {
        CertificateComplianceResultDto dto = new CertificateComplianceResultDto();
        dto.setConnectorName(rule.getConnector().getName());
        dto.setRuleName(rule.getName());
        dto.setRuleDescription(rule.getDescription());
        dto.setStatus(status);
        return dto;
    }

    @Async
    private void bulkUpdateRaProfileComplianceCheck(List<SearchFilterRequestDto> searchFilter) {
        List<Certificate> certificates = (List<Certificate>) searchService.completeSearchQueryExecutor(searchFilter, "Certificate", getSearchableFieldInformation());
        CertificateComplianceCheckDto dto = new CertificateComplianceCheckDto();
        dto.setCertificateUuids(certificates.stream().map(Certificate::getUuid).collect(Collectors.toList()));
        checkCompliance(dto);
    }

    private void certificateComplianceCheck(Certificate certificate) {
        if (certificate.getRaProfile() != null) {
            try {
                complianceService.checkComplianceOfCertificate(certificate);
            } catch (ConnectorException e) {
                logger.error("Error when checking compliance");
            }
        }
    }


    // TODO: Predicate for future use to construct the conditions, and forward to the data repository

    //    private Predicate createPredicate(List<SearchFilterRequestDto> filters) {
//        BooleanBuilder predicate = new BooleanBuilder();
//
//        Path<Certificate> certificate = Expressions.path(Certificate.class, "certificate");
//        try {
//            Class fieldClass = Certificate.class.getField(filters.get(0).getField().getCode()).getClass();
//            Path<Class> certificateField = Expressions.path(Class.class, certificate, filters.get(0).getField().getCode());
//
//
//            PathBuilder<Certificate> entityPath = new PathBuilder<Certificate>(Certificate.class, "certificate");
//            StringPath sp = entityPath.get(filters.get(0).getField().getCode(), fieldClass.getClass());
//
//            predicate.and(entityPath.get.eq(filters.get(0).getValue()));
//
//            Expressions.predicate(Ops.AND, certificateField, filters.get(0).getValue().toString());
//        } catch (NoSuchFieldException e) {
//            e.printStackTrace();
//        }
//        Expressions.predicate(Ops.AND, )
//        filters.get(0).getField().getClass()
//
//        return predicate;
//    }
    private boolean downloadUploadChain(Certificate certificate) {
        List<String> chainCertificates = downloadChainFromAia(certificate);
        List<Certificate> uploadedCertificate = new ArrayList<>();
        if (chainCertificates.isEmpty()) {
            return false;
        }

        for (String cert : chainCertificates) {
            try {
                uploadedCertificate.add(checkCreateCertificate(cert));
            } catch (Exception e) {
                logger.error("Chain already exists");
            }
        }

        if (!uploadedCertificate.isEmpty()) {
            updateIssuer();
            return true;
        } else {
            return false;
        }
    }

    private List<String> downloadChainFromAia(Certificate certificate) {
        List<String> chainCertificates = new ArrayList<>();
        String oldChainUrl = "";
        String chainUrl;
        try {
            X509Certificate certX509 = getX509(certificate.getCertificateContent().getContent());
            while (true) {
                chainUrl = OcspUtil.getChainFromAia(certX509);
                if (oldChainUrl.equals(chainUrl)) {
                    break;
                }
                oldChainUrl = chainUrl;
                if (chainUrl == null || chainUrl.isEmpty()) {
                    break;
                }
                String chainContent = downloadChain(chainUrl);
                if (chainContent.equals("")) {
                    break;
                }
                chainCertificates.add(chainContent);
                certX509 = getX509(chainContent);
            }

        } catch (Exception e) {
            logger.warn("Unable to get the chain of certificate from Authority Information Access");
        }
        return chainCertificates;
    }

    private String downloadChain(String chainUrl) {
        try {
            URL url = new URL(chainUrl);
            URLConnection urlConnection = url.openConnection();
            urlConnection.setConnectTimeout(1000);
            urlConnection.setReadTimeout(1000);
            String fileName = chainUrl.split("/")[chainUrl.split("/").length - 1];
            try (InputStream in = url.openStream();
                 ReadableByteChannel rbc = Channels.newChannel(in);
                 FileOutputStream fos = new FileOutputStream(fileName)) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            } catch (Exception e) {
                logger.error(e.getMessage());
                return "";
            }
            CertificateFactory fac = CertificateFactory.getInstance("X509");
            FileInputStream is = new FileInputStream(fileName);
            X509Certificate cert = (X509Certificate) fac.generateCertificate(is);
            final StringWriter writer = new StringWriter();
            final JcaPEMWriter pemWriter = new JcaPEMWriter(writer);
            pemWriter.writeObject(cert);
            pemWriter.flush();
            pemWriter.close();
            writer.close();
            is.close();
            Path path = Paths.get(fileName);
            Files.deleteIfExists(path);
            return writer.toString();
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
        return "";
    }
}
