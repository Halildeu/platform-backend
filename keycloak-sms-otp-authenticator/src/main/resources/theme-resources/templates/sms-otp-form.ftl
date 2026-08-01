<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        <#-- The channel picks the wording; both lanes render this one template. -->
        <#assign otpChannel = otpChannel!'sms'>
        ${msg(otpChannel + "OtpFormTitle")}
    <#elseif section = "form">
        <form id="kc-sms-otp-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="smsOtp" class="${properties.kcLabelClass!}">${msg("smsOtpLabel")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <p>${msg(otpChannel + "OtpInstruction", maskedRecipient!'***')}</p>
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
    </#if>
</@layout.registrationLayout>
