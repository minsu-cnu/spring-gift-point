package gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jdi.request.DuplicateRequestException;
import gift.product.dto.auth.AccessTokenDto;
import gift.product.dto.auth.LoginMemberIdDto;
import gift.product.dto.auth.MemberDto;
import gift.product.dto.auth.OAuthJwt;
import gift.product.dto.auth.PointRequest;
import gift.product.dto.auth.PointResponse;
import gift.product.dto.auth.RemainingPointResponse;
import gift.product.exception.LoginFailedException;
import gift.product.model.KakaoToken;
import gift.product.model.Member;
import gift.product.property.KakaoProperties;
import gift.product.repository.AuthRepository;
import gift.product.repository.KakaoTokenRepository;
import gift.product.service.AuthService;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Properties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@SuppressWarnings("NonAsciiCharacters")
class AuthServiceTest {

    static final String TEST_OAUTH_ACCESS_TOKEN = "test_oauth_access_token";
    static final String TEST_OAUTH_REFRESH_TOKEN = "test_oauth_refresh_token";
    static final String TEST_AUTHORIZATION_CODE = "test_authorization_code";
    static final String EMAIL = "test@test.com";
    static final String PASSWORD = "test";

    MockWebServer mockWebServer;

    ObjectMapper objectMapper;

    @Mock
    AuthRepository authRepository;

    @Mock
    KakaoTokenRepository kakaoTokenRepository;

    @InjectMocks
    AuthService authService;

    @BeforeEach
    void ObjectMapper_셋팅() {
        this.objectMapper = new ObjectMapper();
    }

    @BeforeEach
    void 카카오_프로퍼티_셋팅() throws IOException {
        Properties properties = new Properties();
        properties.load(getClass().getClassLoader()
            .getResourceAsStream("application-test.properties"));

        String grantType = properties.getProperty("kakao.grant-type");
        String clientId = properties.getProperty("kakao.client-id");
        String redirectUrl = properties.getProperty("kakao.redirect-url");
        String clientSecret = properties.getProperty("kakao.client-secret");

        ReflectionTestUtils.setField(authService,
            "kakaoProperties",
            new KakaoProperties(grantType, clientId, redirectUrl, clientSecret));
    }

    @BeforeEach
    void 시크릿_키_셋팅() {
        ReflectionTestUtils.setField(authService, "SECRET_KEY",
            "Yn2kjibddFAWtnPJ2AFlL8WXmohJMCvigQggaEypa5E=");
    }

    @BeforeEach
    void 가짜_API_서버_구동() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void 가짜_API_서버_종료() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void 회원가입() {
        //given
        MemberDto memberDto = new MemberDto(EMAIL, PASSWORD);
        given(authRepository.existsByEmail(EMAIL)).willReturn(false);

        //when
        authService.register(memberDto);

        //then
        then(authRepository).should().save(any());
    }

    @Test
    void 로그인() {
        //given
        MemberDto memberDto = new MemberDto(EMAIL, PASSWORD);
        given(authRepository.findByEmail(EMAIL)).willReturn(new Member(1L, EMAIL, PASSWORD));
        given(authRepository.existsByEmail(EMAIL)).willReturn(true);

        //when
        AccessTokenDto accessTokenDto = authService.login(memberDto);

        //then
        assertSoftly(softly -> assertThat(accessTokenDto.accessToken()).isNotEmpty());
    }

    @Test
    void 포인트_조회() {
        //given
        LoginMemberIdDto loginMemberIdDto = new LoginMemberIdDto(1L);
        Member member = new Member(loginMemberIdDto.id(), "test@test.com", "test", 1000);
        given(authRepository.findById(any())).willReturn(Optional.of(member));

        //when
        PointResponse pointResponse = authService.getMemberPoint(loginMemberIdDto);

        //then
        assertThat(pointResponse.point()).isEqualTo(member.getPoint());
    }

    @Test
    void 포인트_차감() {
        //given
        LoginMemberIdDto loginMemberIdDto = new LoginMemberIdDto(1L);
        Member member = new Member(loginMemberIdDto.id(), "test@test.com", "test", 1000);
        given(authRepository.findById(loginMemberIdDto.id())).willReturn(Optional.of(member));

        PointRequest pointRequest = new PointRequest(500);
        Member resultMember = new Member(member.getId(),
            member.getEmail(),
            member.getPassword(),
            member.getPoint() - pointRequest.point());
        given(authRepository.save(any(Member.class))).willReturn(resultMember);

        //when
        RemainingPointResponse remainingPointResponse = authService.subtractMemberPoint(pointRequest,
            loginMemberIdDto);

        //then
        assertSoftly(softly -> {
            then(authRepository).should().save(any());
            assertThat(remainingPointResponse.point()).isEqualTo(
                member.getPoint() - pointRequest.point());
        });
    }

    @Test
    void 실패_회원가입_중복() {
        //given
        MemberDto memberDto = new MemberDto(EMAIL, PASSWORD);
        given(authRepository.existsByEmail(EMAIL)).willReturn(false);
        authService.register(memberDto);
        given(authRepository.existsByEmail(EMAIL)).willReturn(true);

        //when, then
        assertThatThrownBy(() -> authService.register(memberDto)).isInstanceOf(
            DuplicateRequestException.class);
    }

    @Test
    void OAuth_토큰_발급() throws JsonProcessingException {
        //given
        OAuthJwt responseBody = new OAuthJwt(TEST_OAUTH_ACCESS_TOKEN,
            TEST_OAUTH_REFRESH_TOKEN);
        mockWebServer.enqueue(new MockResponse().setBody(objectMapper.writeValueAsString(
            responseBody)));

        //when
        String mockUrl = mockWebServer.url("/oauth/token").toString();
        OAuthJwt response = authService.getOAuthToken(TEST_AUTHORIZATION_CODE, mockUrl);

        //then
        assertSoftly(softly -> {
            assertThat(response.accessToken()).isEqualTo(TEST_OAUTH_ACCESS_TOKEN);
            assertThat(response.refreshToken()).isEqualTo(TEST_OAUTH_REFRESH_TOKEN);
        });
    }

    @Test
    void 카카오_유저_회원가입_처리_및_자체_토큰_발급() {
        //given
        String testEmail = "test_email";
        given(authRepository.existsByEmail(testEmail)).willReturn(false);
        given(authRepository.findByEmail(testEmail)).willReturn(new Member(1L,
            testEmail,
            "oauth"));

        String responseBody = "{\"kakao_account\":{\"email\":\"" + testEmail + "\"}}";
        mockWebServer.enqueue(new MockResponse().setBody(responseBody));

        OAuthJwt oAuthJwt = new OAuthJwt(TEST_OAUTH_ACCESS_TOKEN, TEST_OAUTH_REFRESH_TOKEN);

        //when
        String mockUrl = mockWebServer.url("/v2/user/me").toString();
        authService.registerKakaoMember(oAuthJwt, mockUrl);

        //then
        then(authRepository).should().save(any());
        then(kakaoTokenRepository).should().save(any());
    }

    @Test
    void 카카오_유저_연결_끊기() {
        //given
        long testMemberId = 1L;
        String responseBody = "{\"id\":\"" + testMemberId + "\"}";
        mockWebServer.enqueue(new MockResponse().setBody(responseBody));

        LoginMemberIdDto loginMemberIdDto = new LoginMemberIdDto(testMemberId);
        KakaoToken kakaoToken = new KakaoToken(1L,
            testMemberId,
            TEST_OAUTH_ACCESS_TOKEN,
            TEST_OAUTH_REFRESH_TOKEN);

        given(kakaoTokenRepository.findByMemberId(testMemberId)).willReturn(Optional.of(kakaoToken));

        //when
        String mockUrl = mockWebServer.url("/v1/user/unlink").toString();
        Long id = authService.unlinkKakaoAccount(loginMemberIdDto, mockUrl);

        //then
        assertThat(id).isEqualTo(testMemberId);
    }

    @Test
    void 실패_존재하지_않는_회원_로그인() {
        //given
        MemberDto memberDto = new MemberDto(EMAIL, PASSWORD);
        given(authRepository.existsByEmail(EMAIL)).willReturn(false);

        //when, then
        assertThatThrownBy(() -> authService.login(memberDto)).isInstanceOf(
            LoginFailedException.class);
    }

    @Test
    void 실패_카카오_토큰_발급_API_에러() {
        //given
        mockWebServer.enqueue(new MockResponse().setResponseCode(400));

        String mockUrl = mockWebServer.url("/oauth/token").toString();

        //when, then
        assertThatThrownBy(() -> authService.getOAuthToken(TEST_AUTHORIZATION_CODE,
            mockUrl)).isInstanceOf(
            LoginFailedException.class);
    }

    @Test
    void 실패_카카오_사용자_정보_조회_API_에러() {
        //given
        mockWebServer.enqueue(new MockResponse().setResponseCode(400));
        String mockUrl = mockWebServer.url("/v2/user/me").toString();
        OAuthJwt oAuthJwt = new OAuthJwt(TEST_OAUTH_ACCESS_TOKEN, TEST_OAUTH_REFRESH_TOKEN);

        //when, then
        assertThatThrownBy(() -> authService.registerKakaoMember(oAuthJwt,
            mockUrl)).isInstanceOf(
            LoginFailedException.class);
    }

    @Test
    void 실패_카카오_사용자_연결_끊기_API_에러() {
        //given
        mockWebServer.enqueue(new MockResponse().setResponseCode(400));
        String mockUrl = mockWebServer.url("/v1/user/unlink").toString();
        LoginMemberIdDto loginMemberIdDto = new LoginMemberIdDto(1L);
        KakaoToken kakaoToken = new KakaoToken(1L,
            loginMemberIdDto.id(),
            TEST_OAUTH_ACCESS_TOKEN,
            TEST_OAUTH_REFRESH_TOKEN);

        given(kakaoTokenRepository.findByMemberId(loginMemberIdDto.id())).willReturn(Optional.of(
            kakaoToken));

        //when, then
        assertThatThrownBy(() -> authService.unlinkKakaoAccount(loginMemberIdDto,
            mockUrl)).isInstanceOf(
            LoginFailedException.class);
    }

    @Test
    void 실패_존재하지_않는_회원_정보로_포인트_조회() {
        //given
        given(authRepository.findById(any())).willReturn(Optional.empty());

        //when, then
        assertThatThrownBy(() -> authService.getMemberPoint(new LoginMemberIdDto(1L))).isInstanceOf(
            NoSuchElementException.class);
    }

    @Test
    void 실패_존재하지_않는_회원_정보로_포인트_차감() {
        //given
        LoginMemberIdDto loginMemberIdDto = new LoginMemberIdDto(1L);
        PointRequest pointRequest = new PointRequest(500);
        given(authRepository.findById(any())).willReturn(Optional.empty());

        //when, then
        assertThatThrownBy(() -> authService.subtractMemberPoint(pointRequest,
            loginMemberIdDto)).isInstanceOf(
            NoSuchElementException.class);
    }

    @Test
    void 실패_보유_포인트보다_많은_양을_차감() {
        //given
        LoginMemberIdDto loginMemberIdDto = new LoginMemberIdDto(1L);
        Member member = new Member(loginMemberIdDto.id(), "test@test.com", "test", 1000);
        given(authRepository.findById(loginMemberIdDto.id())).willReturn(Optional.of(member));

        PointRequest pointRequest = new PointRequest(9999);

        //when, then
        assertThatThrownBy(() -> authService.subtractMemberPoint(pointRequest,
            loginMemberIdDto)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("회원의 보유 포인트보다 많은 양을 사용할 수 없습니다.");
    }

    @Test
    void 실패_보유_포인트가_1000_미만() {
        //given
        LoginMemberIdDto loginMemberIdDto = new LoginMemberIdDto(1L);
        Member member = new Member(loginMemberIdDto.id(), "test@test.com", "test", 900);
        given(authRepository.findById(loginMemberIdDto.id())).willReturn(Optional.of(member));

        PointRequest pointRequest = new PointRequest(200);

        //when, then
        assertThatThrownBy(() -> authService.subtractMemberPoint(pointRequest,
            loginMemberIdDto)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("회원의 보유 포인트가 1000 이상일 때만 사용할 수 있습니다.");
    }
}
