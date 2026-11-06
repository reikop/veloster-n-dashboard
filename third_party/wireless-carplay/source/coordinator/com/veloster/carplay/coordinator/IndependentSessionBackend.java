/* SPDX-License-Identifier: GPL-3.0-only. Actual Android/USB AP/independent receiver composition. */
package com.veloster.carplay.coordinator;
import android.content.Context;
import com.veloster.carplay.*;
import com.veloster.carplay.control.*;
import com.veloster.carplay.discovery.*;
import com.veloster.carplay.input.TouchHid;
import com.veloster.carplay.media.Api17MicrophoneInput;
import com.veloster.carplay.media.Api17AudioOutput;
import com.veloster.carplay.media.MicrophoneCapabilityProbe;
import com.veloster.carplay.media.Eld16kInputCapability;
import com.veloster.carplay.media.Eld16kOutputCapability;
import com.veloster.carplay.media.Eld48kOutputCapability;
import com.veloster.carplay.tunnel.MicrophoneCaptureLease;
import com.veloster.carplay.pairing.*;
import com.veloster.carplay.advertisement.Api17ReceiverAdvertisement;
import com.veloster.carplay.advertisement.EirProtocol;
import com.veloster.carplay.advertisement.ReceiverAdvertisement;
import java.io.*;
import java.util.*;

/** One service backend. Constructor has no I/O; controller retains this object
 * before coordinator() starts allocating. Main/UI and bonded selection remain
 * explicit application capabilities, never guessed from a discovered device. */
public final class IndependentSessionBackend implements SessionServiceController.RecoveryBackend {
    public enum Stage { STORES, AP_PREPARATION, PRODUCT_IDENTITY, AUDIO_CAPABILITIES, AUTHENTICATION, USER_INTERFACE, ADVERTISEMENT, RECEIVER, CLEANUP }
    public interface Progress {void stage(Stage stage);}
    /** Application policy maps separately probed speech/call sources to the declaration.
     * Probing and physical capture ownership remain in this backend's exact attempt. */
    public interface AudioProfiles {AudioCapabilities create(long speechFormats,long callFormats)throws IOException;}
    /** Opt-in policy: bit26 is offered only after encoder AND decoder probes
     * succeed for the same live microphone owner. Legacy policies stay PCM. */
    public interface Eld16kAudioProfiles extends AudioProfiles {}
    /** Output-only bit25 is independent of the purpose-specific bit26 microphone proof. */
    public interface CompressedAudioProfiles extends Eld16kAudioProfiles {
        AudioCapabilities create(long speechFormats,long callFormats,long outputFormats)throws IOException;
    }
    public interface Selection {Phone open(String apSession,String selectedPhone)throws IOException;}
    public interface RadioSelection extends Selection {/** No radio command during restart reconciliation. */void checkRadioRecovery()throws IOException;}
    public interface RadioPhone extends Phone {
        boolean radioDisabled()throws IOException;
        boolean disableRadio(ReceiverScope scope,int channel,BluetoothRadioHandoff.Readiness readiness)throws IOException;
    }
    public interface Phone extends Closeable {
        long generation();
        /** Memory-only; revoked by settings/bond/Bluetooth lifecycle observers. */
        void checkCurrent()throws IOException;
        Iap2WirelessController.Permission permission()throws IOException;
        Iap2RuntimeController.Listener runtimeListener()throws IOException;
        /** True only for an actually observed request or profile state. */
        boolean disableBluetoothRequested()throws IOException;
        boolean profilesDisconnected()throws IOException;
        /** Selected-device teardown for a phone request on 5GHz; 2.4GHz requires RadioPhone. */
        boolean disconnectProfiles()throws IOException;
    }
    public interface UiHost {
        /** Worker->main creation. On partial failure, clean resources or throw
         * FactoryCleanupException carrying the still-owned cleanup object. */
        UiLease open(ReceiverScope scope,TouchHid touch)throws IOException,InterruptedException;
    }
    public abstract static class UiLease implements Closeable {
        public final AndroidReceiverSession session;
        /** Legacy alias; null for an Activity-independent session lease. */
        public final AndroidReceiverUi ui;
        protected UiLease(AndroidReceiverUi ui){if(ui==null)throw new NullPointerException();this.ui=ui;session=ui.session;}
        protected UiLease(AndroidReceiverSession session){if(session==null)throw new NullPointerException();this.session=session;ui=null;}
        /** Worker->main close acknowledgement; failed dispatch/close must retry. */
        public abstract void close()throws IOException;
    }
    /** Only platform observations and RFCOMM bytes may be substituted by an
     * in-package integration harness. Production assembly/lifetimes stay here. */
    interface Boundaries {
        ReceiverProductConfiguration.Platform productPlatform(Context context)throws IOException;
        RfcommConnector rfcommConnector()throws IOException;
        ReceiverAdvertisement advertisement(Context context,ReceiverScope scope,Api17ReceiverAdvertisement.Current current)throws IOException;
    }
    interface EnrollmentBoundaries extends Boundaries {
        ReceiverProductConfiguration.EnrollmentPlatform enrollmentPlatform(Context context)throws IOException;
        ReceiverAdvertisement enrollmentAdvertisement(Context context,EirProtocol.EnrollmentScope scope,Api17ReceiverAdvertisement.Current current)throws IOException;
    }
    private static final Boundaries ANDROID=new EnrollmentBoundaries(){
        public ReceiverProductConfiguration.EnrollmentPlatform enrollmentPlatform(Context context){return new AndroidReceiverProductPlatform(context);}
        public ReceiverAdvertisement enrollmentAdvertisement(Context context,EirProtocol.EnrollmentScope scope,Api17ReceiverAdvertisement.Current current){return new Api17ReceiverAdvertisement(context,scope,current);}
        public ReceiverProductConfiguration.Platform productPlatform(Context context){return new AndroidReceiverProductPlatform(context);}
        public RfcommConnector rfcommConnector()throws IOException{return Api17RfcommConnector.create();}
        public ReceiverAdvertisement advertisement(Context context,ReceiverScope scope,Api17ReceiverAdvertisement.Current current){return new Api17ReceiverAdvertisement(context,scope,current);}
    };
    private final Context context;private final File directory;private final ApManagerClient manager;
    private final ApConfiguration apConfig;private final ReceiverProductConfiguration.Spec spec;
    private final Selection selection;private final UiHost uiHost;private final AudioCapabilities audio;private final Progress progress;
    private final Boundaries boundaries;
    private final AudioProfiles audioProfiles;
    private final RegisteredReceiverControl registry=new RegisteredReceiverControl();
    private SessionJournal journal,apJournal;private ProcessOwner process;private SessionCoordinator coordinator;private PairingStore pairings;private BootstrapIdentityStore bootstrapStore;
    private Prepared prepared;private Enrollment enrollment;private boolean closed;
    public IndependentSessionBackend(Context context,File appPrivateDirectory,ApManagerClient manager,ApConfiguration apConfig,
        ReceiverProductConfiguration.Spec spec,Selection selection,UiHost uiHost,AudioCapabilities audio,Progress progress){
        this(context,appPrivateDirectory,manager,apConfig,spec,selection,uiHost,audio,progress,ANDROID);
    }
    public IndependentSessionBackend(Context context,File appPrivateDirectory,ApManagerClient manager,ApConfiguration apConfig,
        ReceiverProductConfiguration.Spec spec,Selection selection,UiHost uiHost,AudioCapabilities audio,Progress progress,AudioProfiles profiles){
        this(context,appPrivateDirectory,manager,apConfig,spec,selection,uiHost,audio,progress,ANDROID,profiles);
    }
    IndependentSessionBackend(Context context,File appPrivateDirectory,ApManagerClient manager,ApConfiguration apConfig,
        ReceiverProductConfiguration.Spec spec,Selection selection,UiHost uiHost,AudioCapabilities audio,Progress progress,Boundaries boundaries){
        this(context,appPrivateDirectory,manager,apConfig,spec,selection,uiHost,audio,progress,boundaries,null);
    }
    IndependentSessionBackend(Context context,File appPrivateDirectory,ApManagerClient manager,ApConfiguration apConfig,
        ReceiverProductConfiguration.Spec spec,Selection selection,UiHost uiHost,AudioCapabilities audio,Progress progress,Boundaries boundaries,AudioProfiles profiles){
        if(context==null||appPrivateDirectory==null||manager==null||apConfig==null||spec==null||selection==null||uiHost==null||audio==null)throw new NullPointerException();
        if(boundaries==null)throw new NullPointerException();this.boundaries=boundaries;this.audioProfiles=profiles;
        Context application=context.getApplicationContext();this.context=application==null?context:application;
        directory=appPrivateDirectory;this.manager=manager;this.apConfig=apConfig;this.spec=spec;this.selection=selection;this.uiHost=uiHost;this.audio=audio;this.progress=progress;
    }
    private void stage(Stage value){if(progress!=null)try{progress.stage(value);}catch(RuntimeException ignored){} }
    private static final class EldProof {
        final Eld16kInputCapability input;final Eld16kOutputCapability output;
        EldProof(Eld16kInputCapability input,Eld16kOutputCapability output){this.input=input;this.output=output;}
        long formats(Api17MicrophoneInput.Purpose purpose,long pcm)throws IOException{
            output.checkCurrent();return input.physicalRate(purpose)==0?pcm:pcm|Eld16kInputCapability.FORMAT;
        }
        void close(){output.close();}
    }
    private EldProof probeEld(MicrophoneCapabilityProbe.Result microphone)throws IOException{
        if(!(audioProfiles instanceof Eld16kAudioProfiles))return null;
        Eld16kOutputCapability output=null;
        try{
            Eld16kInputCapability input=Eld16kInputCapability.probe(context,microphone);
            output=Eld16kOutputCapability.probe(context,microphone);
            microphone.checkCurrent();output.checkCurrent();
            return new EldProof(input,output);
        }catch(Eld16kOutputCapability.CleanupException cleanup){
            if(output!=null)output.close();throw cleanup;
        }catch(IOException unavailable){
            if(output!=null)output.close();
            // Codec absence can retain the existing PCM declaration. A retired
            // attempt is an error and must not turn into a fallback capability.
            microphone.checkCurrent();return null;
        }
    }
    private Eld48kOutputCapability probeEld48(MicrophoneCapabilityProbe.Result microphone,
        Eld48kOutputCapability.Current owner)throws IOException{
        if(!(audioProfiles instanceof CompressedAudioProfiles))return null;
        try{return Eld48kOutputCapability.probe(context,owner);}
        catch(Eld48kOutputCapability.CleanupException cleanup){throw cleanup;}
        catch(IOException unavailable){
            // Unsupported output may retain PCM, but a withdrawn owner may not.
            microphone.checkCurrent();owner.checkCurrent(context);return null;
        }
    }
    private static void requireEld48Proof(AudioCapabilities declared,Eld48kOutputCapability proof)throws IOException{
        for(AudioCapabilities.Output row:declared.outputs())if((row.formats&Eld48kOutputCapability.FORMAT)!=0){
            if(proof==null)throw new IOException("ELD stereo48 output was declared without a current probe");
            proof.checkCurrent();
        }
    }
    private static File child(File root,String name)throws IOException{
        File value=new File(root,name).getAbsoluteFile();if(!value.equals(value.getCanonicalFile()))throw new IOException("Private receiver directory unavailable");
        if(!value.isDirectory()&&!value.mkdir())throw new IOException("Private receiver directory unavailable");
        // Persist the new directory's name as well as the journal's contents.
        // Repeat on retries: a previous parent fsync may have failed after mkdir.
        new Api17DirectorySync().sync(root);return value;
    }
    public SessionCoordinator coordinator()throws IOException{
        if(closed)throw new IOException("Backend closed");if(coordinator!=null)return coordinator;stage(Stage.STORES);
        File root=directory.getAbsoluteFile();if(!root.equals(root.getCanonicalFile())||!root.isDirectory())throw new IOException("Private backend directory required");
        if(journal==null)journal=new SessionJournal(child(root,"journal"),new Api17DirectorySync());
        if(apJournal==null)apJournal=new SessionJournal(child(root,"ap-journal"),new Api17DirectorySync());
        if(process==null)process=ProcessOwner.current();
        if(pairings==null)pairings=new PairingStore(child(root,"pairing"));
        if(bootstrapStore==null)bootstrapStore=new BootstrapIdentityStore(child(root,"bootstrap"));
        ReceiverControl recovery=new ProcessRecoveryControl(registry,journal,ProcessOwner.linux(),new ProcessRecoveryControl.OwnedApRelease(){public void release(String ap)throws IOException{
            releaseOwnedAp(ap);
        }});
        coordinator=new SessionCoordinator(journal,recovery,new SessionCoordinator.Generation(){public boolean current(String ap)throws IOException{
            Prepared value=prepared;if(value==null||!value.ap.equals(ap))return false;value.current();return true;
        }},process);return coordinator;
    }
    private void releaseOwnedAp(String ap)throws IOException{manager.ownedCommand("stop-owned",ap,"released",60000);}
    static Iap2WirelessController.HandoffObservation observeHandoff(String ap,String selected,boolean wifi,boolean established,int channel,Phone phone)throws IOException{
        boolean disable=phone.disableBluetoothRequested();
        // Optional OEM profile APIs are relevant only after all selected-phone
        // Wi-Fi/runtime gates, and only when the controller requires teardown.
        boolean disconnected=false;
        if(wifi&&established){
            if(channel<=14){if(!(phone instanceof RadioPhone))throw new IOException("2.4GHz requires owned whole-stack Bluetooth handoff");disconnected=((RadioPhone)phone).radioDisabled();}
            else if(disable)disconnected=phone.profilesDisconnected();
        }
        return new Iap2WirelessController.HandoffObservation(ap,selected,wifi,established,established,disable,disconnected);
    }
    /** The receiver journal covers BEGIN; this separate durable record covers
     * death during prepare-owned/start-owned, including an unknown IPC outcome. */
    public void recoverOwnedResources()throws IOException{
        if(prepared!=null||enrollment!=null)return;SessionJournal.Record previous=apJournal.pending();
        if(previous!=null){if(previous.process==null||!ProcessOwner.linux().dead(previous.process))throw new IOException("AP owner process is not proven dead");
            releaseOwnedAp(previous.apSession);apJournal.clearAfterStop(previous,new ReceiverControl.Reply(0,0));}
        if(selection instanceof RadioSelection)((RadioSelection)selection).checkRadioRecovery();
    }
    public SessionCoordinator.Factory sessionFactory()throws IOException{throw new IOException("Prepare the selected AP/phone first");}
    public SessionCoordinator.Factory prepare(String ap,String phone)throws IOException,InterruptedException{
        if(closed||prepared!=null||enrollment!=null||coordinator==null)throw new IOException("Previous preparation must be discarded");
        if(apJournal.pending()!=null)throw new IOException("Previous owned AP recovery required");
        if(ap==null||!ap.matches("[0-9a-f]{32}"))throw new IOException("Invalid AP generation");phone=RfcommConnector.normalizeAddress(phone);
        final Prepared value=new Prepared(ap,phone);prepared=value;stage(Stage.AP_PREPARATION);
        value.phone=selection.open(ap,phone);if(value.phone==null)throw new IOException("Selected phone generation unavailable");
        value.generation=value.phone.generation();if(value.generation<1)throw new IOException("Selected phone generation unavailable");
        value.phone.checkCurrent();value.owned=manager.owned(apConfig,ap);
        value.apRecord=new SessionJournal.Record(WirelessSession.newOwner(),ap,phone,process);
        apJournal.prepare(value.apRecord); // durable BEFORE any root prepare/start
        value.owned.start();value.current();
        return new SessionCoordinator.Factory(){public SessionCoordinator.Handle open(SessionJournal.Record record,ReceiverControl fenced)throws IOException,InterruptedException{
            if(!record.apSession.equals(value.ap)||!record.phone.equals(value.selected)||value.attempt!=null)throw new IOException("Prepared receiver scope mismatch");
            value.current();Attempt attempt=new Attempt(value,record);value.attempt=attempt;
            try{return attempt.open(fenced);}catch(IOException failure){throw new SessionCoordinator.FactoryCleanupException("Receiver assembly failed",attempt,failure);}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new SessionCoordinator.FactoryCleanupException("Receiver assembly interrupted",attempt,failure);}
            catch(RuntimeException failure){throw new SessionCoordinator.FactoryCleanupException("Receiver assembly unavailable",attempt,failure);}
            catch(LinkageError failure){throw new SessionCoordinator.FactoryCleanupException("Receiver assembly API unavailable",attempt,failure);}
        }};
    }
    public void discardPrepared()throws IOException{
        Prepared value=prepared;if(value==null)return;stage(Stage.CLEANUP);
        if(coordinator!=null&&(coordinator.state()==SessionCoordinator.State.RUNNING||coordinator.state()==SessionCoordinator.State.RECOVERING||coordinator.state()==SessionCoordinator.State.CLEANUP_REQUIRED))
            throw new IOException("Coordinator cleanup must complete before AP discard");
        value.close();if(prepared==value)prepared=null;
    }
    public void close()throws IOException{
        if(closed)return;discardEnrollment();discardPrepared();
        if(bootstrapStore!=null){bootstrapStore.close();bootstrapStore=null;}
        if(pairings!=null){pairings.close();pairings=null;}
        apConfig.close();
        // Controller owns the coordinator lock. A partial initialization has no
        // returned coordinator, so this backend must finish that journal itself.
        if(coordinator==null&&journal!=null){journal.close();journal=null;}
        if(apJournal!=null){apJournal.close();apJournal=null;}closed=true;
    }
    /** Memory-only foreground/radio/settings grant, independent of all selected-phone permissions. */
    public interface EnrollmentConsent {void checkCurrent()throws IOException;}
    /** Caller retains the backend before invoking this method, including failed preparation. */
    public Enrollment prepareEnrollment(EirProtocol.EnrollmentScope scope,EnrollmentConsent consent)throws IOException,InterruptedException{
        if(scope==null||consent==null)throw new NullPointerException();
        if(closed||prepared!=null||enrollment!=null||coordinator==null||coordinator.state()!=SessionCoordinator.State.IDLE||apJournal.pending()!=null)
            throw new IOException("Previous receiver/AP cleanup required before enrollment");
        consent.checkCurrent();Enrollment next=new Enrollment(scope,consent);enrollment=next;next.open();return next;
    }
    public void discardEnrollment()throws IOException{Enrollment value=enrollment;if(value!=null){value.close();if(enrollment==value)enrollment=null;}}
    /** No RFCOMM, receiver, credentials disclosure or selected-phone scope exists in this lease. */
    public final class Enrollment implements Closeable {
        public final EirProtocol.EnrollmentScope scope;
        private final EnrollmentConsent consent;private final AuthBindings authentication=new AuthBindings();
        private ApOwnedSession owned;private SessionJournal.Record record;private ApStationMonitor monitor;
        private MicrophoneCapabilityProbe probe;private ReceiverAdvertisement advertisement;private OemAuthBinding auth;
        private EldProof eld;
        private Eld48kOutputCapability eld48;
        private ReceiverProductConfiguration.EnrollmentProduct product;private AudioCapabilities declared;
        private volatile boolean retired;private boolean ready,monitorReady;
        private Enrollment(EirProtocol.EnrollmentScope scope,EnrollmentConsent consent){this.scope=scope;this.consent=consent;}
        private void baseCurrent()throws IOException{
            consent.checkCurrent();if(retired||enrollment!=this||closed)throw new IOException("Enrollment withdrawn");
            if(owned!=null){if(owned.state()!=ApOwnedSession.State.READY)throw new IOException("Enrollment AP withdrawn");}
            if(monitor!=null){monitor.checkCurrent(scope.ap,index());if(monitorReady)ReceiverProductConfiguration.requireEnrollmentAp(scope,monitor.latest(),index(),android.os.SystemClock.elapsedRealtime());}
            if(auth!=null)auth.checkCurrent();
        }
        private int index()throws IOException{return owned.interfaceIndex();}
        public void checkCurrent()throws IOException{baseCurrent();if(eld48!=null)eld48.checkCurrent();if(!ready||advertisement==null)throw new IOException("Enrollment is not ready");advertisement.checkCurrent();}
        private void open()throws IOException,InterruptedException{
            baseCurrent();stage(Stage.AP_PREPARATION);owned=manager.owned(apConfig,scope.ap);
            record=SessionJournal.Record.enrollment(scope.owner,scope.ap,scope.enrollmentId,process);apJournal.prepare(record);
            owned.start();baseCurrent();monitor=manager.watch(scope.ap,index());
            long observationDeadline=android.os.SystemClock.elapsedRealtime()+5000;
            while(monitor.latest()==null){baseCurrent();if(android.os.SystemClock.elapsedRealtime()>=observationDeadline)throw new IOException("Fresh enrollment AP observation unavailable");Thread.sleep(25);}
            monitorReady=true;baseCurrent();stage(Stage.PRODUCT_IDENTITY);
            product=ReceiverProductConfiguration.captureEnrollment(spec,pairings,scope,"usbwifi0",owned.localAddress(),index(),boundaries instanceof EnrollmentBoundaries?((EnrollmentBoundaries)boundaries).enrollmentPlatform(context):new AndroidReceiverProductPlatform(context),
                new ReceiverProductConfiguration.EnrollmentGeneration(){public void checkCurrent(EirProtocol.EnrollmentScope s)throws IOException{if(!scope.equals(s))throw new IOException("Foreign enrollment product");baseCurrent();}});
            stage(Stage.AUDIO_CAPABILITIES);declared=audio;
            if(audioProfiles!=null||audio.hasInput()){
                probe=new MicrophoneCapabilityProbe(context,new MicrophoneCaptureLease.ScopeLease(){public void checkCurrent()throws IOException{baseCurrent();}});probe.start();
                if(!probe.awaitStopped(15000))throw new IOException("Enrollment microphone preparation still pending");
                final MicrophoneCapabilityProbe.Result result=probe.result();result.checkCurrent();baseCurrent();
                long speech=result.formats(Api17MicrophoneInput.Purpose.SPEECH_RECOGNITION),call=result.formats(Api17MicrophoneInput.Purpose.TELEPHONY);
                eld=probeEld(result);
                if(eld!=null){speech=eld.formats(Api17MicrophoneInput.Purpose.SPEECH_RECOGNITION,speech);call=eld.formats(Api17MicrophoneInput.Purpose.TELEPHONY,call);}
                eld48=probeEld48(result,new Eld48kOutputCapability.Current(){public void checkCurrent(Context normalized)throws IOException{
                    if(normalized!=context)throw new IOException("Foreign enrollment audio context");result.checkCurrent();baseCurrent();
                }});
                long output=eld48==null?0:Eld48kOutputCapability.FORMAT;
                declared=audioProfiles==null?probedInputs(audio,speech,call):
                    audioProfiles instanceof CompressedAudioProfiles?((CompressedAudioProfiles)audioProfiles).create(speech,call,output):audioProfiles.create(speech,call);
                if(declared==null)throw new IOException("Enrollment audio declaration unavailable");
            }
            requireEld48Proof(declared,eld48);
            // Open each declared Android output/decoder on this worker, muted and stopped.
            // A buffer-size or a configured bitmask alone is not a positive readiness probe.
            java.util.Set<Long> tested=new java.util.HashSet<Long>();
            for(AudioCapabilities.Output output:declared.outputs())for(int bit=0;bit<63;bit++)if((output.formats&(1L<<bit))!=0&&tested.add(1L<<bit)){
                baseCurrent();Api17AudioOutput sink=(Api17AudioOutput)new Api17AudioOutput.Factory().open(com.veloster.carplay.tunnel.AudioWireFormat.parse(1L<<bit));
                try{baseCurrent();}finally{sink.closeChecked();}
            }
            stage(Stage.AUTHENTICATION);auth=authentication.open();byte[] certificate=auth.authentication().certificate();
            try{if(certificate==null||certificate.length==0)throw new IOException("Enrollment OEM certificate unavailable");}finally{if(certificate!=null)Arrays.fill(certificate,(byte)0);}
            baseCurrent();stage(Stage.ADVERTISEMENT);
            Api17ReceiverAdvertisement.Current publicationCurrent=new Api17ReceiverAdvertisement.Current(){public void checkCurrent()throws IOException{baseCurrent();if(eld48!=null)eld48.checkCurrent();}};
            advertisement=boundaries instanceof EnrollmentBoundaries?((EnrollmentBoundaries)boundaries).enrollmentAdvertisement(context,scope,publicationCurrent):new Api17ReceiverAdvertisement(context,scope,publicationCurrent);
            if(advertisement==null)throw new IOException("Enrollment Bluetooth carrier unavailable");
            advertisement.start(10000);ready=true;checkCurrent();
        }
        /** Drain this exact native contribution before AP/auth release or a selected-phone attempt. */
        public void close()throws IOException{
            retired=true;ready=false;
            if(advertisement!=null){advertisement.close();try{if(!advertisement.awaitStopped(1000))throw new IOException("Enrollment EIR cleanup still pending");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Enrollment EIR cleanup interrupted",e);}advertisement=null;}
            if(eld48!=null){eld48.close();eld48=null;}
            if(eld!=null){eld.close();eld=null;}
            if(probe!=null){probe.close();try{if(!probe.awaitStopped(1000))throw new IOException("Enrollment microphone cleanup still pending");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Enrollment microphone cleanup interrupted",e);}probe=null;}
            if(monitor!=null){monitor.close();try{if(!monitor.awaitStopped(1000))throw new IOException("Enrollment AP watcher cleanup still pending");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Enrollment watcher cleanup interrupted",e);}monitor=null;}
            authentication.close();auth=null;
            if(owned!=null){owned.close();owned=null;}
            if(record!=null){SessionJournal.Record pending=apJournal.pending();
                if(pending!=null){if(!record.same(pending))throw new IOException("Enrollment AP journal owner changed");apJournal.clearOwnedResource(record);}
                else apJournal.syncOwnedResourceDirectory();record=null;
            }
        }
    }
    private final class Prepared implements Closeable {
        final String ap,selected;Phone phone;long generation;ApOwnedSession owned;SessionJournal.Record apRecord;Attempt attempt;boolean retired;
        Prepared(String ap,String selected){this.ap=ap;this.selected=selected;}
        void current()throws IOException{
            if(retired||prepared!=this||phone==null||owned==null||owned.state()!=ApOwnedSession.State.READY)throw new IOException("AP preparation unavailable");phone.checkCurrent();
            if(generation<1||phone.generation()!=generation)throw new IOException("Selected phone generation changed");
        }
        public void close()throws IOException{
            retired=true;if(attempt!=null){attempt.close();attempt=null;}if(owned!=null){owned.close();owned=null;}
            if(apRecord!=null){SessionJournal.Record pending=apJournal.pending();if(pending!=null)apJournal.clearAfterStop(apRecord,new ReceiverControl.Reply(0,0));apRecord=null;}
            if(phone!=null){phone.close();phone=null;}
        }
    }
    private final class Attempt implements Closeable {
        final Prepared preparation;final ReceiverScope scope;final int index;
        final AuthBindings authentication=new AuthBindings();
        ReceiverProductConfiguration product;ApStationMonitor monitor;ApPeerAdmission admission;BootstrapIdentityEnvironment bootstrap;
        RfcommConnector connector;UiLease ui;Closeable partialUi;IndependentReceiverControl receiver;RegisteredReceiverLease lease;
        ReceiverMetadata metadata;ReceiverRuntime runtime;boolean released;
        volatile ReceiverAdvertisement advertisement;volatile boolean advertisementStarted,advertisementRetiring;
        MicrophoneCapabilityProbe captureProbe;MicrophoneCapabilityProbe.Result captureResult;AudioCapabilities attemptAudio;
        EldProof eld;
        Eld48kOutputCapability eld48;
        Attempt(Prepared preparation,SessionJournal.Record record)throws IOException{
            this.preparation=preparation;index=preparation.owned.interfaceIndex();
            scope=new ReceiverScope(record.owner,record.apSession,preparation.generation,record.phone,"usbwifi0",preparation.owned.localAddress());
        }
        void baseCurrent()throws IOException{if(released)throw new IOException("Receiver attempt retired");preparation.current();if(monitor!=null)monitor.checkCurrent(scope.apSession,index);}
        void current()throws IOException{
            baseCurrent();synchronized(this){ReceiverAdvertisement value=advertisement;
                // Serialize the last active check with intentional retirement.
                // Otherwise a media worker can observe !retiring, pause, then
                // check an already closed lease and tear down a valid handoff.
                if(value!=null&&advertisementStarted&&!advertisementRetiring)value.checkCurrent();
            }
        }
        /** Withdraw discovery once this exact phone has authenticated over Wi-Fi.
         * Stop checking advertising before intentional withdrawal: media workers
         * must remain valid while the carrier acknowledges its own cleanup. */
        void retireAdvertisement()throws IOException{
            ReceiverAdvertisement value;synchronized(this){advertisementRetiring=true;value=advertisement;}
            if(value==null)return;
            value.close();try{if(!value.awaitStopped(1000))throw new IOException("Bluetooth advertisement cleanup still pending");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Bluetooth advertisement cleanup interrupted",e);}
            synchronized(this){if(advertisement==value)advertisement=null;}
        }
        void exact(ReceiverScope value)throws IOException{BootstrapIdentityStore.same(scope,value);current();}
        SessionCoordinator.Handle open(ReceiverControl fenced)throws IOException,InterruptedException{
            current();stage(Stage.PRODUCT_IDENTITY);product=ReceiverProductConfiguration.capture(spec,pairings,scope,index,boundaries.productPlatform(context),
                new ReceiverProductConfiguration.Generation(){public void checkCurrent(ReceiverScope value)throws IOException{exact(value);}});
            attemptAudio=audio;
            if(audioProfiles!=null||audio.hasInput()){
                stage(Stage.AUDIO_CAPABILITIES);
                // Retain the worker before start, including failed/late device allocation.
                // The advertisement's current check also checks the audio proof.
                // Keep the probe's owner fence at the base scope to avoid a cycle.
                captureProbe=new MicrophoneCapabilityProbe(context,new MicrophoneCaptureLease.ScopeLease(){public void checkCurrent()throws IOException{baseCurrent();}});
                captureProbe.start();
                if(!captureProbe.awaitStopped(15000))throw new IOException("Microphone capability preparation still pending");
                captureResult=captureProbe.result();captureResult.checkCurrent();current();
                long speech=captureResult.formats(Api17MicrophoneInput.Purpose.SPEECH_RECOGNITION),call=captureResult.formats(Api17MicrophoneInput.Purpose.TELEPHONY);
                eld=probeEld(captureResult);
                if(eld!=null){speech=eld.formats(Api17MicrophoneInput.Purpose.SPEECH_RECOGNITION,speech);call=eld.formats(Api17MicrophoneInput.Purpose.TELEPHONY,call);}
                final MicrophoneCapabilityProbe.Result ownerResult=captureResult;
                eld48=probeEld48(ownerResult,new Eld48kOutputCapability.Current(){public void checkCurrent(Context normalized)throws IOException{
                    if(normalized!=context)throw new IOException("Foreign receiver audio context");ownerResult.checkCurrent();Attempt.this.baseCurrent();
                }});
                long output=eld48==null?0:Eld48kOutputCapability.FORMAT;
                if(audioProfiles instanceof CompressedAudioProfiles)attemptAudio=((CompressedAudioProfiles)audioProfiles).create(speech,call,output);
                else if(audioProfiles!=null)attemptAudio=audioProfiles.create(speech,call);
                else attemptAudio=probedInputs(audio,speech,call);
                if(attemptAudio==null)throw new IOException("Audio profile policy returned no declaration");
            }
            requireEld48Proof(attemptAudio,eld48);
            stage(Stage.AUTHENTICATION);OemAuthBinding auth=authentication.open();connector=boundaries.rfcommConnector();
            if(connector==null)throw new IOException("RFCOMM boundary unavailable");
            monitor=manager.watch(scope.apSession,index);
            Iap2WirelessController.Environment environment=new Iap2WirelessController.Environment(){
                public Iap2WirelessController.ApSnapshot accessPoint()throws IOException{return snapshot();}
                public boolean current(String ap)throws IOException{if(!scope.apSession.equals(ap))return false;Attempt.this.current();return true;}
                public Iap2WirelessController.ReceiverIdentity receiver(String ap)throws IOException{if(!scope.apSession.equals(ap))throw new IOException("Foreign AP");Attempt.this.current();return receiver==null?null:receiver.receiverIdentity();}
                public Iap2WirelessController.HandoffObservation handoff(String ap,String phone)throws IOException{
                    if(!scope.apSession.equals(ap)||!scope.selectedBluetooth.equals(phone))throw new IOException("Foreign handoff");Attempt.this.current();
                    boolean established=receiver!=null&&receiver.sessionStarted();ApStationSnapshot observed=monitor.latest();
                    // The tracker has verified the phone/media grant; an unrelated
                    // DHCP observation alone must never claim selected-phone Wi-Fi.
                    boolean wifi=established&&observed!=null&&observed.associationToken!=null&&observed.ipv4!=null;
                    // Complete removal while Bluetooth is still up, before the
                    // 2.4GHz handoff can intentionally disable the entire radio.
                    if(wifi)retireAdvertisement();
                    return observeHandoff(ap,phone,wifi,established,apConfig.channel,preparation.phone);
                }
                public boolean disconnectBluetoothProfiles(String ap,String phone)throws IOException{
                    if(!scope.apSession.equals(ap)||!scope.selectedBluetooth.equals(phone))throw new IOException("Foreign profiles");Attempt.this.current();
                    if(apConfig.channel<=14){
                        if(!(preparation.phone instanceof RadioPhone))throw new IOException("Whole-stack Bluetooth handoff unavailable");
                        BluetoothRadioHandoff.Readiness readiness=new BluetoothRadioHandoff.Readiness(){public void checkReady(ReceiverScope supplied)throws IOException{
                            BootstrapIdentityStore.same(scope,supplied);Attempt.this.current();
                            if(receiver==null||!receiver.sessionStarted())throw new IOException("Authenticated runtime/media handoff withdrawn");
                            ApStationSnapshot observed=monitor.latest();if(observed==null||observed.associationToken==null||observed.ipv4==null)throw new IOException("Selected Wi-Fi association withdrawn");
                            Attempt.this.current();
                        }};
                        readiness.checkReady(scope);return ((RadioPhone)preparation.phone).disableRadio(scope,apConfig.channel,readiness);
                    }
                    return preparation.phone.disconnectProfiles();
                }
                public void transportIdentifiers(String ap,String phone,String usb)throws IOException{if(!scope.apSession.equals(ap)||!scope.selectedBluetooth.equals(phone))throw new IOException("Foreign bootstrap callback");Attempt.this.current();}
            };
            bootstrap=new BootstrapIdentityEnvironment(scope,environment,bootstrapStore,new BootstrapIdentityStore.Current(){public void checkCurrent(ReceiverScope value)throws IOException{exact(value);}});
            admission=ApPeerAdmission.withBootstrap(scope,monitor,index,new ApPeerAdmission.Generation(){public void checkCurrent(ReceiverScope value)throws IOException{exact(value);}},bootstrap);
            final TouchHid touch=new TouchHid(product.metadata.main.width,product.metadata.main.height);stage(Stage.USER_INTERFACE);
            try{ui=uiHost.open(scope,touch);}catch(SessionCoordinator.FactoryCleanupException partial){partialUi=partial.pendingResources;throw partial;}
            if(ui==null)throw new IOException("Receiver UI unavailable");current();
            metadata=new ReceiverMetadata(pairings,product.metadata,ui.session.initialModes(),attemptAudio,touch,ui.session.consumerDeclaration());
            receiver=new IndependentReceiverControl(scope,new IndependentReceiverControl.Generation(){public void checkCurrent(ReceiverScope value)throws IOException{exact(value);}},
                new IndependentReceiverControl.Factory(){public ReceiverRuntime open(ReceiverScope value,RuntimeSessionTracker tracker)throws IOException{
                    exact(value);if(eld!=null)eld.output.checkCurrent();if(eld48!=null)eld48.checkCurrent();stage(Stage.RECEIVER);runtime=new ReceiverRuntime(pairings,admission,ui.session.approval,metadata,ui.session.screens,ui.session.audio,tracker,null,0,ui.session.input,
                        attemptAudio.hasInput()?new com.veloster.carplay.media.MicrophoneStreamFactory(context,attemptAudio,captureResult,eld==null?null:eld.input):null,ui.session.sessionControlFactory());return runtime;
                }},runtimeFactory(),1000);
            lease=registry.lease(receiver,1000,new Closeable(){public void close()throws IOException{releaseResources();}});
            stage(Stage.ADVERTISEMENT);
            advertisement=boundaries.advertisement(context,scope,new Api17ReceiverAdvertisement.Current(){public void checkCurrent()throws IOException{baseCurrent();if(eld48!=null)eld48.checkCurrent();}});
            if(advertisement==null)throw new IOException("Bluetooth advertisement carrier unavailable");
            // Retain partial binding before start. This advertises receiver
            // capability; peer acceptance and the network listener follow the
            // authenticated RFCOMM bootstrap, not this local registration.
            advertisement.start(10000);advertisementStarted=true;current();
            WirelessSession session=new WirelessSession(new WirelessSession.RfcommTransport(connector),fenced,auth.authentication(),bootstrap,
                new Iap2WirelessController.Consent(){public Iap2WirelessController.Permission decision(String phone,String ap)throws IOException{
                    if(!scope.selectedBluetooth.equals(phone)||!scope.apSession.equals(ap))throw new IOException("Foreign permission request");current();return preparation.phone.permission();
                }},product.identity,scope.selectedBluetooth,Iap2WirelessController.StartMode.BONJOUR_CONTROL,null,scope.owner);
            return new SessionCoordinator.Handle(session,this);
        }
        Iap2WirelessController.ApSnapshot snapshot()throws IOException{
            current();ApCredentials credentials=preparation.owned.credentials();byte[] ssid=null,secret=null;char[] password=null;
            try{if(!scope.apSession.equals(credentials.sessionToken))throw new IOException("AP credentials changed");ssid=credentials.ssidBytes();secret=credentials.passphraseBytes();password=new char[secret.length];
                for(int i=0;i<secret.length;i++)password[i]=(char)(secret[i]&255);current();return new Iap2WirelessController.ApSnapshot(scope.apSession,new String(ssid,"UTF-8"),password,credentials.channel,scope.localAddress.getHostAddress(),null);
            }finally{credentials.close();if(ssid!=null)Arrays.fill(ssid,(byte)0);if(secret!=null)Arrays.fill(secret,(byte)0);if(password!=null)Arrays.fill(password,'\0');}
        }
        RuntimeControlHost.Factory runtimeFactory(){return new RuntimeControlHost.Factory(){
            public RuntimeControlHost.Resources open(final PairVerify.VerifiedSession session)throws IOException,InterruptedException{
                session.assertCurrent();if(!scope.contains(session.context()))throw new IOException("Foreign verified runtime");current();
                final OemAuthBinding binding=authentication.open();boolean handed=false;RuntimeControlHost.Resources acquired=null;
                try{final Iap2RuntimeController.Listener listener=preparation.phone.runtimeListener();
                final com.veloster.carplay.location.LocationSource location=com.veloster.carplay.location.Api17GpsLocationSource.create(context);
                RuntimeControlHost.Resources result=new RuntimeControlHost.Resources(binding.authentication(),product.identity,new Iap2RuntimeController.Environment(){
                    public void checkCurrent()throws IOException{session.assertCurrent();current();}
                    public Iap2WirelessController.ApSnapshot accessPoint()throws IOException{checkCurrent();return snapshot();}
                    public Iap2WirelessController.ReceiverIdentity receiver()throws IOException{checkCurrent();return metadata.receiverIdentity();}
                    public void transportIdentifiers(String observed,String usb)throws IOException{checkCurrent();/* RuntimeControlHost forwards actual fields to VerifiedSession. */}
                },listener,location){
                    public void close()throws IOException{try{location.close();}finally{binding.close();}}
                };acquired=result;session.assertCurrent();current();handed=true;return result;}finally{if(!handed){Closeable pendingResources=acquired==null?binding:acquired;try{pendingResources.close();}catch(IOException pending){throw new SessionCoordinator.FactoryCleanupException("Runtime resource cleanup pending",pendingResources,pending);}}}
            }
        };}
        public void close()throws IOException{
            if(lease!=null){lease.close();return;}if(released)return;
            if(receiver!=null){receiver.close();try{if(!receiver.awaitStopped(1000))throw new IOException("Receiver still draining");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Receiver cleanup interrupted",e);}}
            releaseResources();
        }
        void releaseResources()throws IOException{
            if(released)return;
            retireAdvertisement();
            if(eld48!=null){eld48.close();eld48=null;}
            if(eld!=null){eld.close();eld=null;}
            if(captureProbe!=null){captureProbe.close();try{if(!captureProbe.awaitStopped(1000))throw new IOException("Microphone capability cleanup still pending");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Microphone capability cleanup interrupted",e);}captureProbe=null;captureResult=null;}
            if(admission!=null)admission.close();else if(monitor!=null)monitor.close();
            if(monitor!=null)try{if(!monitor.awaitStopped(1000))throw new IOException("AP watcher still draining");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("AP watcher cleanup interrupted",e);}
            if(ui!=null){ui.close();ui=null;}if(partialUi!=null){partialUi.close();partialUi=null;}
            if(bootstrap!=null){bootstrap.close();bootstrap=null;}if(connector!=null){connector.close();connector=null;}
            authentication.close();released=true;
        }
    }
    /** Preserve caller-selected output profiles, intersecting declared inputs with actual source results. */
    private static AudioCapabilities probedInputs(AudioCapabilities configured,long speech,long call)throws IOException{
        List<AudioCapabilities.Input> inputs=new ArrayList<AudioCapabilities.Input>();
        for(Object value:configured.wire()){
            Map<?,?> row=(Map<?,?>)value;Object declared=row.get("audioInputFormats");if(!(declared instanceof Number))continue;
            String purpose=(String)row.get("audioType");long allowed="speechRecognition".equals(purpose)?speech:"telephony".equals(purpose)?call:0;
            long formats=((Number)declared).longValue()&allowed;if(formats!=0)inputs.add(new AudioCapabilities.Input(((Number)row.get("type")).intValue(),purpose,formats));
        }
        return new AudioCapabilities(configured.outputs(),inputs);
    }
    private final class AuthBindings implements Closeable {
        final List<OemAuthBinding> values=new ArrayList<OemAuthBinding>();boolean closing;
        OemAuthBinding open()throws IOException,InterruptedException{
            synchronized(this){if(closing)throw new IOException("Authentication pool closing");}
            OemAuthBinding binding;
            try{binding=OemAuthBinding.open(context,10000);}catch(OemAuthBinding.PendingCleanupException pending){synchronized(this){values.add(pending.binding);}throw pending;}
            synchronized(this){values.add(binding);if(closing){binding.close();throw new IOException("Authentication pool closed during allocation");}}return binding;
        }
        public synchronized void close()throws IOException{closing=true;while(!values.isEmpty()){values.get(0).close();values.remove(0);}}
    }
}
