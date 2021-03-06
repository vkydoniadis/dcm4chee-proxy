/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.proxy;

import java.io.IOException;
import java.rmi.UnexpectedException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.dcm4che3.conf.api.ApplicationEntityCache;
import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.AssociationHandler;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.IncompatibleConnectionException;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.TransferCapability.Role;
import org.dcm4che3.net.pdu.AAbort;
import org.dcm4che3.net.pdu.AAssociateAC;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.CommonExtendedNegotiation;
import org.dcm4che3.net.pdu.ExtendedNegotiation;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.pdu.RoleSelection;
import org.dcm4che3.net.pdu.UserIdentityAC;
import org.dcm4chee.proxy.common.RetryObject;
import org.dcm4chee.proxy.conf.ForwardOption;
import org.dcm4chee.proxy.conf.ForwardRule;
import org.dcm4chee.proxy.conf.ProxyAEExtension;
import org.dcm4chee.proxy.conf.Schedule;
import org.dcm4chee.proxy.utils.ForwardConnectionUtils;
import org.dcm4chee.proxy.utils.ForwardRuleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Michael Backhaus <michael.backhaus@agfa.com>
 * @author Hesham Elbadawi <bsdreko@gmail.com>
 */
public class ProxyAssociationHandler extends AssociationHandler {

    private final String[] TRANSFER_SYNTAX_ACCEPTANCE_OREDER_IMAGE = {
            "1.2.840.10008.1.2.4.70", "1.2.840.10008.1.2.4.57",
            "1.2.840.10008.1.2.1", "1.2.840.10008.1.2",
            "1.2.840.10008.1.2.4.50", "1.2.840.10008.1.2.4.51" };
    private final String[] TRANSFER_SYNTAX_ACCEPTANCE_OREDER_VIDEO = {
            "1.2.840.10008.1.2.4.50", "1.2.840.10008.1.2.4.101",
            "1.2.840.10008.1.2.4.100" };
    private final String[] TRANSFER_SYNTAX_ACCEPTANCE_OREDER_WAV = {
            "1.2.840.10008.1.2.1", "1.2.840.10008.1.2" };
    private final String[] TRANSFER_SYNTAX_ACCEPTANCE_OREDER_SR = {
            "1.2.840.10008.1.2.1.99", "1.2.840.10008.1.2.1",
            "1.2.840.10008.1.2" };
    private final String[] TRANSFER_SYNTAX_ACCEPTANCE_OTHER = {
            "1.2.840.10008.1.2.1", "1.2.840.10008.1.2"

    };
    private static final Logger LOG = LoggerFactory
            .getLogger(ProxyAssociationHandler.class);

    private ApplicationEntityCache aeCache;

    public ProxyAssociationHandler(ApplicationEntityCache aeCache) {
        this.aeCache = aeCache;
    }

    @Override
    protected AAssociateAC makeAAssociateAC(Association as, AAssociateRQ rq,
            UserIdentityAC userIdentity) throws IOException {
        ProxyAEExtension proxyAEE = as.getApplicationEntity().getAEExtension(
                ProxyAEExtension.class);
        filterForwardRulesOnNegotiationRQ(as, rq, proxyAEE);
        if (!proxyAEE.isAssociationFromDestinationAET(as)
                && sendNow(as, proxyAEE)) {
            ForwardRule forwardRule = proxyAEE.getCurrentForwardRules(as)
                    .get(0);
            LOG.info(
                    "{}: directly forwarding to {} based on forward rule \"{}\"",
                    new Object[] { as, forwardRule.getDestinationAETitles(),
                            forwardRule.getCommonName() });
            return forwardAAssociateRQ(as, rq, proxyAEE);
        }
        // Scheduled forwarding

        as.setProperty(ProxyAEExtension.FILE_SUFFIX, ".dcm");
//        rq.addRoleSelection(new RoleSelection(
//                UID.StorageCommitmentPushModelSOPClass, true, true));

        if(!proxyAEE.isAssociationFromDestinationAET(as)){
        try {
            return MatchTransferCapability(as, rq, proxyAEE);
        } catch (ConfigurationException e) {
            LOG.error(
                    "Minimal Transfer capability error {}\n Unable to use minimal transfer capability matching for forwarding",
                    e);
        }
        }
            return super.makeAAssociateAC(as, rq, userIdentity);
        
    }

    private AAssociateAC MatchTransferCapability(Association as,
            AAssociateRQ rq, ProxyAEExtension proxyAEE)
            throws ConfigurationException {

        AAssociateAC ac = new AAssociateAC();
        ac.setCalledAET(rq.getCalledAET());
        ac.setCallingAET(rq.getCallingAET());
        Connection conn = as.getConnection();
        ac.setMaxPDULength(conn.getReceivePDULength());
        ac.setMaxOpsInvoked(minZeroAsMax(rq.getMaxOpsInvoked(),
                conn.getMaxOpsPerformed()));
        ac.setMaxOpsPerformed(minZeroAsMax(rq.getMaxOpsPerformed(),
                conn.getMaxOpsInvoked()));
        UserIdentityAC acIDRsp = authenticateAE(rq);
        ac.setUserIdentityAC(acIDRsp);
        @SuppressWarnings("unchecked")
        List<ForwardRule> lstRules = (List<ForwardRule>) as
                .getProperty(ProxyAEExtension.FORWARD_RULES);
        LOG.debug("Will be forwarded to the following destinations:");
        List<ApplicationEntity> listAEs = new LinkedList<ApplicationEntity>();
        for (ForwardRule rule : lstRules)
            for (String destination : rule.getDestinationAETitles()) {
                LOG.info(destination);
                try {
                    listAEs.add(aeCache.findApplicationEntity(destination));
                } catch (ConfigurationException e) {
                    LOG.error(
                            "Missing Application Entity configuration for AETitle = {} - {}",
                            destination, e);
                }
            }
        for (PresentationContext prq : rq.getPresentationContexts()) {
            try {
                addMinimallySupported(proxyAEE, rq, prq, ac, listAEs);
            } catch (UnexpectedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        // debug minimal TS
        LOG.debug("supporting the following minimal Presentation contexts:");
        for (PresentationContext pc : ac.getPresentationContexts())
            LOG.debug(pc.toString());
        // debug check for failed sop configuration
        if (LOG.isDebugEnabled()) {
            int fullReject = ac.getPresentationContexts().size();
            for (PresentationContext pc : ac.getPresentationContexts()) {
                if (pc.isAccepted())
                    fullReject--;
            }
            if (fullReject == ac.getPresentationContexts().size())

                LOG.error("All transfer syntax were rejected, Unable to get matching minimal set of transfer syntax from destination AEs possible reason is missconfiguration for supported sop classes ");
            else if (fullReject > 0) {
                LOG.debug("Some minimal presentation context were accepted");
            } else {
                LOG.debug("All minimal presentation context were accepted");
            }
        }
        //fix for role selection
        for(RoleSelection rs: rq.getRoleSelections())
        {
            
            ac.addRoleSelection(new RoleSelection(rs.getSOPClassUID(), true, true));    
        }
        
        return ac;
    }

    private UserIdentityAC authenticateAE(AAssociateRQ rq) {
        if (rq.getUserIdentityRQ() != null) {
            if (rq.getUserIdentityRQ().isPositiveResponseRequested()) {
                // TODO UserIdentity Authentication
                // for now accept all
                return new UserIdentityAC(new byte[0]);
            } else {
                return null;
            }

        }
        return null;
    }

    private List<String> getCommonTS(String abstractSyntax,
            String[] toBeCheckedTS, List<ApplicationEntity> listAEs,
            ProxyAEExtension proxyAEE) {
        List<String> accepted = new ArrayList<String>();
        TransferCapability tc;
        int reject = 0;

        for (String ts : toBeCheckedTS) {
            for (ApplicationEntity ae : listAEs) {
                if (ae.getTransferCapabilities().size() > 0) {
                    tc = ae.getTransferCapabilityFor(abstractSyntax, Role.SCP);
                    if (tc == null || !tc.containsTransferSyntax(ts)) {
                        reject++;
                    }
                } else {
                    tc = proxyAEE.getApplicationEntity()
                            .getTransferCapabilityFor(abstractSyntax, Role.SCP);
                    if (tc == null || !tc.containsTransferSyntax(ts)) {
                        reject++;
                    }
                }
            }

            if (reject == 0)
                accepted.add(ts);

        }
        return accepted;
    }

    private boolean isSupportedAbstractSyntax(String abstractSyntax,
            List<ApplicationEntity> listAEs, ProxyAEExtension prxAE) {

        for (ApplicationEntity ae : listAEs)
            if (ae.getTransferCapabilityFor(abstractSyntax, Role.SCP) != null)
                continue;
            else if (prxAE.getApplicationEntity().getTransferCapabilityFor(
                    abstractSyntax, Role.SCP) != null
                    && ae.getTransferCapabilities().size() == 0)
                continue;
            else
                return false;

        return true;
    }

    private void addMinimallySupported(ProxyAEExtension proxyAEE,
            AAssociateRQ rq, PresentationContext prq, AAssociateAC ac,
            List<ApplicationEntity> listAEs) throws UnexpectedException {
        boolean acceptedByOrder = false;
        String abstractSyntax = prq.getAbstractSyntax();

        // first check for abstract syntax rejection
        if (!isSupportedAbstractSyntax(abstractSyntax, listAEs, proxyAEE)) {
            ac.addPresentationContext(new PresentationContext(prq.getPCID(),
                    PresentationContext.ABSTRACT_SYNTAX_NOT_SUPPORTED, prq
                            .getTransferSyntax()));
            return;
        }
        // second check for transfer syntax not supported
        List<String> acceptedTS = getCommonTS(abstractSyntax,
                prq.getTransferSyntaxes(), listAEs, proxyAEE);

        if (acceptedTS.size() == 0) {
            // reject for ts reason
            ac.addPresentationContext(new PresentationContext(prq.getPCID(),
                    PresentationContext.TRANSFER_SYNTAX_NOT_SUPPORTED, prq
                            .getTransferSyntax()));
            return;
        } else if (acceptedTS.size() > 1) {
            // Preferred ts acceptance
            String[] order = null;
            if (UID.nameOf(abstractSyntax).toLowerCase()
                    .contains("image storage")) {
                order = TRANSFER_SYNTAX_ACCEPTANCE_OREDER_IMAGE;
            } else if (UID.nameOf(abstractSyntax).toLowerCase()
                    .contains("video")) {
                order = TRANSFER_SYNTAX_ACCEPTANCE_OREDER_VIDEO;
            } else if (UID.nameOf(abstractSyntax).toLowerCase()
                    .contains("waveform")) {
                order = TRANSFER_SYNTAX_ACCEPTANCE_OREDER_WAV;
            } else if (UID.nameOf(abstractSyntax).toLowerCase()
                    .contains("sr storage")) {
                order = TRANSFER_SYNTAX_ACCEPTANCE_OREDER_SR;
            } else {
                order = TRANSFER_SYNTAX_ACCEPTANCE_OTHER;
            }

            for (String currentInOrder : order){
                for (String ts : acceptedTS) {
                    if (ts.compareToIgnoreCase(currentInOrder) == 0) {
                        ac.addPresentationContext(new PresentationContext(prq
                                .getPCID(), PresentationContext.ACCEPTANCE, ts));
                        acceptedByOrder = true;
                    }
                }
                if(acceptedByOrder)
                    break;
            }
            if (!acceptedByOrder) {
                ac.addPresentationContext(new PresentationContext(
                        prq.getPCID(),
                        PresentationContext.ACCEPTANCE,
                        getCompressed(acceptedTS) != null ? getCompressed(acceptedTS)
                                : acceptedTS.get(0)));
            }

        } else {
            // just accept this one
            ac.addPresentationContext(new PresentationContext(prq.getPCID(),
                    PresentationContext.ACCEPTANCE, acceptedTS.get(0)));
        }

    }

    private String getCompressed(List<String> acceptedTS) {
        for (String ts : acceptedTS) {
            if (UID.nameOf(ts).contains("JPEG")
                    || UID.nameOf(ts).contains("MPEG2")
                    || UID.nameOf(ts).contains("Deflated")
                    || UID.nameOf(ts).contains("MPEG-4")) {
                return ts;
            }
        }
        return null;
    }

    // Currently Extended negotiations should not be supported
    /*
     * private PresentationContext addExtendedNegiotiations(String requestedTS,
     * TransferCapability tc, AAssociateRQ rq, AAssociateAC ac,
     * PresentationContext rqpc) { String as = rqpc.getAbstractSyntax(); int
     * pcid = rqpc.getPCID();
     * 
     * byte[] info = negotiate(rq.getExtNegotiationFor(as), tc); if (info !=
     * null) ac.addExtendedNegotiation(new ExtendedNegotiation(as, info));
     * return new PresentationContext(pcid, PresentationContext.ACCEPTANCE,
     * requestedTS); }
     * 
     * private byte[] negotiate(ExtendedNegotiation exneg, TransferCapability
     * tc) { if (exneg == null) return null;
     * 
     * StorageOptions storageOptions = tc.getStorageOptions(); if
     * (storageOptions != null) return
     * storageOptions.toExtendedNegotiationInformation();
     * 
     * EnumSet<QueryOption> queryOptions = tc.getQueryOptions(); if
     * (queryOptions != null) { EnumSet<QueryOption> commonOpts =
     * QueryOption.toOptions(exneg); commonOpts.retainAll(queryOptions); return
     * QueryOption.toExtendedNegotiationInformation(commonOpts); } return null;
     * }
     */

    private List<ForwardRule> filterForwardRulesOnNegotiationRQ(Association as,
            AAssociateRQ rq, ProxyAEExtension proxyAEE) {
        List<ForwardRule> returnList = ForwardRuleUtils
                .filterForwardRulesByCallingAET(proxyAEE, rq.getCallingAET());
        as.setProperty(ProxyAEExtension.FORWARD_RULES, returnList);
        return returnList;
    }

    @Override
    protected void onClose(Association as) {
        super.onClose(as);
        Object forwardAssociationProperty = as
                .getProperty(ProxyAEExtension.FORWARD_ASSOCIATION);
        if (forwardAssociationProperty == null)
            return;

        Association[] asInvoked;
        if (forwardAssociationProperty instanceof Association) {
            ArrayList<Association> list = new ArrayList<Association>(1);
            list.add((Association) forwardAssociationProperty);
            asInvoked = list.toArray(new Association[1]);
        } else {
            @SuppressWarnings("unchecked")
            HashMap<String, Association> fwdAssocs = (HashMap<String, Association>) forwardAssociationProperty;
            asInvoked = fwdAssocs.values().toArray(
                    new Association[fwdAssocs.size()]);
        }
        for (Association assoc : asInvoked) {
            if (assoc != null && assoc.isRequestor())
                try {
                    assoc.waitForOutstandingRSP();
                    assoc.release();
                } catch (Exception e) {
                    LOG.debug("Failed to release {} ({})", new Object[] {
                            assoc, e.getMessage() });
                }
        }
    }

    private boolean sendNow(Association as, ProxyAEExtension proxyAEE) {
        List<ForwardRule> matchingForwardRules = proxyAEE
                .getCurrentForwardRules(as);
        return (matchingForwardRules.size() == 1
                && !forwardBasedOnTemplates(matchingForwardRules)
                && matchingForwardRules.get(0).getDimse().isEmpty()
                && matchingForwardRules.get(0).getSopClasses().isEmpty()
                && (matchingForwardRules.get(0).getCallingAETs().isEmpty() || matchingForwardRules
                        .get(0).getCallingAETs().contains(as.getCallingAET()))
                && matchingForwardRules.get(0).getDestinationAETitles().size() == 1 && isAvailableDestinationAET(
                    matchingForwardRules.get(0).getDestinationAETitles().get(0),
                    proxyAEE))
                && matchingForwardRules.get(0).getMpps2DoseSrTemplateURI() == null
                && !matchingForwardRules.get(0).isRunPIXQuery();
    }

    private boolean forwardBasedOnTemplates(List<ForwardRule> forwardRules) {
        for (ForwardRule rule : forwardRules)
            if (rule.getReceiveSchedule().isNow(new GregorianCalendar()))
                if (rule.containsTemplateURI())
                    return true;
        return false;
    }

    private boolean isAvailableDestinationAET(String destinationAET,
            ProxyAEExtension proxyAEE) {
        HashMap<String, ForwardOption> forwardOptions = proxyAEE
                .getForwardOptions();
        if (!forwardOptions.keySet().contains(destinationAET))
            return true;

        Schedule forwardAETSchedule = forwardOptions.get(destinationAET)
                .getSchedule();
        return forwardAETSchedule.isNow(new GregorianCalendar());
    }

    private AAssociateAC forwardAAssociateRQ(Association asAccepted,
            AAssociateRQ rq, ProxyAEExtension proxyAEE) throws IOException {
        ForwardRule forwardRule = proxyAEE.getCurrentForwardRules(asAccepted)
                .get(0);
        List<ForwardRule> fwrList = new ArrayList<ForwardRule>();
        fwrList.add(forwardRule);
        asAccepted.setProperty(ProxyAEExtension.FORWARD_RULES, fwrList);
        String calledAET = forwardRule.getDestinationAETitles().get(0);
        AAssociateAC ac = new AAssociateAC();
        ac.setCalledAET(rq.getCalledAET());
        ac.setCallingAET(rq.getCallingAET());
        try {
            AAssociateRQ forwardRq = copyOf(rq);
            String callingAET = (forwardRule.getUseCallingAET() == null) ? asAccepted
                    .getCallingAET() : forwardRule.getUseCallingAET();
            Association asInvoked = ForwardConnectionUtils
                    .openForwardAssociation(proxyAEE, asAccepted, forwardRule,
                            callingAET, calledAET, forwardRq, aeCache);
            asAccepted.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION,
                    asInvoked);
            asInvoked.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION,
                    asAccepted);
            asInvoked.setProperty(ProxyAEExtension.FORWARD_RULE, forwardRule);
            AAssociateAC acCalled = asInvoked.getAAssociateAC();
            if (forwardRule.isExclusiveUseDefinedTC()) {
                AAssociateAC acProxy = super.makeAAssociateAC(asAccepted,
                        forwardRq, null);
                LOG.debug("{}: generating subset of transfer capabilities",
                        asAccepted);
                for (PresentationContext pcCalled : acCalled
                        .getPresentationContexts()) {
                    final PresentationContext pcLocal = acProxy
                            .getPresentationContext(pcCalled.getPCID());
                    LOG.debug(
                            "{}: use {} : {}",
                            new Object[] { asAccepted,
                                    pcCalled.getTransferSyntaxes(),
                                    pcLocal.isAccepted() });
                    ac.addPresentationContext(pcLocal.isAccepted() ? pcCalled
                            : pcLocal);
                }
            } else
                addPresentationContext(asAccepted, proxyAEE, calledAET, ac,
                        callingAET, asInvoked, acCalled);
            for (RoleSelection rs : acCalled.getRoleSelections())
                ac.addRoleSelection(rs);
            for (ExtendedNegotiation extNeg : acCalled
                    .getExtendedNegotiations())
                ac.addExtendedNegotiation(extNeg);
            for (CommonExtendedNegotiation extNeg : acCalled
                    .getCommonExtendedNegotiations())
                ac.addCommonExtendedNegotiation(extNeg);
            ac.setMaxPDULength(asInvoked.getConnection().getReceivePDULength());
            ac.setMaxOpsInvoked(minZeroAsMax(rq.getMaxOpsInvoked(), asInvoked
                    .getConnection().getMaxOpsPerformed()));
            ac.setMaxOpsPerformed(minZeroAsMax(rq.getMaxOpsPerformed(),
                    asInvoked.getConnection().getMaxOpsInvoked()));
            return ac;
        } catch (ConfigurationException e) {
            LOG.error("Unable to load configuration for destination AET: ",
                    e.getMessage());
            if (LOG.isDebugEnabled())
                e.printStackTrace();
            throw new AAbort(AAbort.UL_SERIVE_PROVIDER, 0);
        } catch (AAssociateRJ rj) {
            return handleNegotiateConnectException(asAccepted, rq, ac,
                    calledAET, rj, RetryObject.AAssociateRJ.getSuffix() + "0",
                    rj.getReason(), proxyAEE);
        } catch (AAbort aa) {
            return handleNegotiateConnectException(asAccepted, rq, ac,
                    calledAET, aa, RetryObject.AAbort.getSuffix() + "0",
                    aa.getReason(), proxyAEE);
        } catch (IOException e) {
            return handleNegotiateConnectException(asAccepted, rq, ac,
                    calledAET, e, RetryObject.ConnectionException.getSuffix()
                            + "0", 0, proxyAEE);
        } catch (InterruptedException e) {
            LOG.error("Unexpected exception: ", e);
            throw new AAbort(AAbort.UL_SERIVE_PROVIDER, 0);
        } catch (IncompatibleConnectionException e) {
            return handleNegotiateConnectException(asAccepted, rq, ac,
                    calledAET, e,
                    RetryObject.IncompatibleConnectionException.getSuffix()
                            + "0", 0, proxyAEE);
        } catch (GeneralSecurityException e) {
            return handleNegotiateConnectException(asAccepted, rq, ac,
                    calledAET, e,
                    RetryObject.GeneralSecurityException.getSuffix() + "0", 0,
                    proxyAEE);
        }
    }

    private void addPresentationContext(Association asAccepted,
            ProxyAEExtension proxyAEE, String calledAET, AAssociateAC ac,
            String callingAET, Association asCalled, AAssociateAC acCalled) {
        if (isConnectionWithChangedTC(proxyAEE, calledAET, callingAET)) {
            for (PresentationContext pc : acCalled.getPresentationContexts()) {
                String abstractSyntaxCalled = asCalled.getAAssociateRQ()
                        .getPresentationContext(pc.getPCID())
                        .getAbstractSyntax();
                if (asAccepted.getAAssociateRQ()
                        .containsPresentationContextFor(abstractSyntaxCalled))
                    ac.addPresentationContext(pc);
            }
        } else {
            for (PresentationContext pc : acCalled.getPresentationContexts())
                ac.addPresentationContext(pc);
        }
    }

    private boolean isConnectionWithChangedTC(ProxyAEExtension proxyAEE,
            String calledAET, String callingAET) {
        HashMap<String, ForwardOption> forwardOptions = proxyAEE
                .getForwardOptions();
        return forwardOptions.containsKey(calledAET)
                && forwardOptions.get(calledAET).isConvertEmf2Sf()
                || forwardOptions.containsKey(callingAET)
                && forwardOptions.get(callingAET).isConvertEmf2Sf();
    }

    private AAssociateRQ copyOf(AAssociateRQ rq) {
        AAssociateRQ copy = new AAssociateRQ();
        for (PresentationContext pc : rq.getPresentationContexts())
            copy.addPresentationContext(pc);
        copy.setReservedBytes(rq.getReservedBytes());
        copy.setProtocolVersion(rq.getProtocolVersion());
        copy.setMaxPDULength(rq.getMaxPDULength());
        copy.setMaxOpsInvoked(rq.getMaxOpsInvoked());
        copy.setMaxOpsPerformed(rq.getMaxOpsPerformed());
        copy.setCalledAET(rq.getCalledAET());
        copy.setCallingAET(rq.getCallingAET());
        copy.setApplicationContext(rq.getApplicationContext());
        copy.setImplClassUID(rq.getImplClassUID());
        copy.setImplVersionName(rq.getImplVersionName());
        copy.setUserIdentityRQ(rq.getUserIdentityRQ());
        for (RoleSelection rs : rq.getRoleSelections())
            copy.addRoleSelection(rs);
        for (ExtendedNegotiation en : rq.getExtendedNegotiations())
            copy.addExtendedNegotiation(en);
        for (CommonExtendedNegotiation cen : rq.getCommonExtendedNegotiations())
            copy.addCommonExtendedNegotiation(cen);
        return copy;
    }

    static int minZeroAsMax(int i1, int i2) {
        return i1 == 0 ? i2 : i2 == 0 ? i1 : Math.min(i1, i2);
    }

    private AAssociateAC handleNegotiateConnectException(Association as,
            AAssociateRQ rq, AAssociateAC ac, String destinationAETitle,
            Exception e, String suffix, int reason, ProxyAEExtension proxyAEE)
            throws IOException, AAbort {
        as.clearProperty(ProxyAEExtension.FORWARD_ASSOCIATION);
        LOG.error(as + ": unable to connect to {}: {}", new Object[] {
                destinationAETitle, e.getMessage() });
        if (proxyAEE.isAcceptDataOnFailedAssociation()) {
            as.setProperty(ProxyAEExtension.FILE_SUFFIX, suffix);
            return super.makeAAssociateAC(as, rq, null);
        }
        throw new AAbort(AAbort.UL_SERIVE_PROVIDER, reason);
    }
}
