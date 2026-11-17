package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.onboarding.service.OnboardingService;
import com.loanrun.setting.service.CsvExportService;
import com.loanrun.user.domain.DemoUser;
import com.loanrun.user.repository.DemoUserRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class UnavailablePortTest {
    @Test
    void absentTeamOnePortsFailExplicitlyInsteadOfReturningEmptySuccessfulData() {
        var beans = new StaticListableBeanFactory();
        var users = mock(DemoUserRepository.class);
        when(users.findById(1L)).thenReturn(Optional.of(mock(DemoUser.class)));
        var onboarding = new OnboardingService(users, mock(FinanceProfileQueryPort.class),
            beans.getBeanProvider(LoanQueryPort.class), beans.getBeanProvider(PlanQueryPort.class), mock(RevisionPort.class), Clock.systemUTC());
        assertThatThrownBy(() -> onboarding.get(1)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.status().value()).isEqualTo(503));
        var csv = new CsvExportService(beans.getBeanProvider(LoanQueryPort.class));
        assertThatThrownBy(() -> csv.export(1, "csv", null, null)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
