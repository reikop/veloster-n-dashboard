/* SPDX-License-Identifier: GPL-3.0-only. Versioned local Binder contract; no radio acknowledgement. */
package com.veloster.carplay.advertisement;

public final class EirProtocol {
    private EirProtocol(){}
    public static final int VERSION=2,ENROLLMENT_VERSION=3,START=1,STATUS=2,STOP=3;
    public static final String DESCRIPTOR="com.veloster.carplay.eir.IReceiverAdvertisement";
    public static final String PERMISSION="com.veloster.carplay.permission.CONTROL_EIR";
    public static final String CARRIER_PACKAGE="com.android.bluetooth",SERVICE="com.veloster.carplay.eir.CarPlayEirService",CLIENT_PACKAGE="com.veloster.carplayhub";
    public static final int CARRIER_UID=1002,MIN_CARRIER_VERSION=21,MIN_CLIENT_VERSION=23;
    public static final String SIGNER_SHA256="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    public static final int PENDING=0,REGISTERED_UNCONFIRMED=1,STOPPED=2,FAILED=3,CLEANUP_REQUIRED=4,REJECTED=5;
    public static final int NATIVE_IDLE=0,NATIVE_QUEUED=1,NATIVE_REGISTERED=2,NATIVE_REMOVED=3,NATIVE_DETACHED=13;
    public static class Scope {
        public final String owner,ap,phone;public final long selectionGeneration;
        public final String enrollmentId;
        public Scope(String owner,String ap,long generation,String phone){this(owner,ap,generation,phone,null);}
        private Scope(String owner,String ap,long generation,String phone,String enrollment){
            if(owner==null||!owner.matches("[0-9a-f]{32}")||ap==null||!ap.matches("[0-9a-f]{32}")||owner.equals(ap)||generation<1
                ||(enrollment==null?(phone==null||!phone.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")):
                   (phone!=null||!enrollment.matches("[0-9a-f]{32}")||enrollment.equals(owner)||enrollment.equals(ap))))throw new IllegalArgumentException("Invalid EIR owner scope");
            this.owner=owner;this.ap=ap;selectionGeneration=generation;this.phone=phone;enrollmentId=enrollment;
        }
        public final boolean enrollment(){return enrollmentId!=null;}
        public final int version(){return enrollment()?ENROLLMENT_VERSION:VERSION;}
        public final boolean equals(Object other){if(!(other instanceof Scope))return false;Scope s=(Scope)other;return owner.equals(s.owner)&&ap.equals(s.ap)&&same(phone,s.phone)&&same(enrollmentId,s.enrollmentId)&&selectionGeneration==s.selectionGeneration;}
        private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
        public final int hashCode(){return owner.hashCode()*31+ap.hashCode()+(enrollment()?enrollmentId.hashCode():phone.hashCode());}
    }
    /** Foreground add-device capability. It grants discovery only, never a phone/session permission. */
    public static final class EnrollmentScope extends Scope {
        public EnrollmentScope(String owner,String ap,long generation,String enrollmentId){super(owner,ap,generation,null,enrollmentId);}
    }
    public static final class Snapshot {
        public final Scope scope;public final long requestSequence,carrierGeneration;public final int state,nativeStatus;
        public final boolean localContribution,localStopped,controllerConfirmed;public final String failure;
        public Snapshot(Scope scope,long generation,int state,int nativeStatus,boolean contribution,boolean stopped,boolean confirmed,String failure){
            this(scope,0,generation,state,nativeStatus,contribution,stopped,confirmed,failure);
        }
        public Snapshot(Scope scope,long sequence,long generation,int state,int nativeStatus,boolean contribution,boolean stopped,boolean confirmed,String failure){
            if(scope==null||sequence<0||generation<0||state<PENDING||state>REJECTED||nativeStatus< -1||nativeStatus>13||confirmed||contribution&&stopped
                ||failure==null||failure.length()>160)throw new IllegalArgumentException("Invalid EIR snapshot");
            this.scope=scope;requestSequence=sequence;carrierGeneration=generation;this.state=state;this.nativeStatus=nativeStatus;localContribution=contribution;localStopped=stopped;controllerConfirmed=false;this.failure=failure;
        }
        public boolean ready(){return state==REGISTERED_UNCONFIRMED&&carrierGeneration>0&&nativeStatus==NATIVE_REGISTERED&&localContribution&&!localStopped;}
        public boolean stopped(){return state==STOPPED&&localStopped&&!localContribution;}
    }
}
