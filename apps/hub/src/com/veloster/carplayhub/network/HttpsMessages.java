package com.veloster.carplayhub.network;
import com.veloster.network.ModernHttps;
import com.veloster.i18n.UiText;
public final class HttpsMessages {
 private HttpsMessages(){}
 public static String failure(ModernHttps.Failure error){String message;
  switch(error.reason){
   case CANCELLED:message="HTTPS 검사를 취소했습니다.";break;
   case NETWORK:message="Wi-Fi 연결이 없거나 검사 중 연결이 변경됐습니다.";break;
   case DNS:message="서버 이름을 찾지 못했습니다. 핫스팟 DNS와 인터넷 연결을 확인하세요.";break;
   case CONNECT:message="서버에 연결하지 못했습니다. 인터넷 연결과 서버 주소를 확인하세요.";break;
   case TIMEOUT:message="서버 응답 시간이 초과됐습니다.";break;
   case CERTIFICATE:message="인증서 또는 서버 이름 검증에 실패했습니다. 기기 시각과 서버 인증서를 확인하세요.";break;
   case TLS:message="서버와 암호화 연결을 맺지 못했습니다. 서버의 TLS 지원을 확인하세요.";break;
   case HTTP:message="HTTPS 연결은 됐지만 서버가 요청한 파일을 제공하지 않았습니다.";break;
   case POLICY:message="허용되지 않은 주소 또는 리디렉션입니다.";break;
   case LIMIT:message="응답 파일이 허용 크기를 초과했습니다.";break;
   case INTEGRITY:message="다운로드 파일의 크기 또는 해시가 일치하지 않습니다.";break;
   case PROTOCOL:message="서버 응답이 불완전하거나 올바르지 않습니다.";break;
   default:message="HTTPS 모듈 또는 로컬 파일을 확인하세요.";
  }
  return UiText.t(message)+(error.detail==0?"":" ("+error.detail+")");
 }
}
