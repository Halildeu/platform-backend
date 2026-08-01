<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("smsOtpFormTitle")}
    <#elseif section = "form">
        <form id="kc-sms-otp-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="smsOtp" class="${properties.kcLabelClass!}">${msg("smsOtpLabel")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <p>${msg("smsOtpInstruction", maskedPhone!'***')}</p>
                    <input id="smsOtp" name="smsOtp" type="text" inputmode="numeric"
                           pattern="[0-9]*" maxlength="6" autocomplete="one-time-code"
                           class="${properties.kcInputClass!}" autofocus autocapitalize="off"/>
                </div>
            </div>
            <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       type="submit" value="${msg("doSubmit")}"/>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                        type="submit" name="resend" value="resend" formnovalidate>${msg("smsOtpResend")}</button>
            </div>
        </form>

        <#--
          The method switcher. Without it this screen is a dead end: a user
          whose code never arrives has only "Resend", and no way back to the
          authenticator app or the other channel — on an account that carries
          requires-mfa, that is a lockout with a spinner in front of it.
          Keycloak renders this on its own OTP form; our template simply never
          carried it, which is also why the earlier live round showed no
          alternatives on the SMS screen.
        -->
        <#if auth?? && auth.showTryAnotherWayLink()>
            <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                <input type="hidden" name="tryAnotherWay" value="on"/>
                <a href="#" id="try-another-way"
                   onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;"
                >${msg("doTryAnotherWay")}</a>
            </form>
        </#if>
    </#if>
</@layout.registrationLayout>
