<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Finance\UseCase\CompleteBankAuthorization;
use Symfony\Component\HttpFoundation\RedirectResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Where the bank sends the user back once they have decided.
 *
 * Deliberately open: the person arriving here is coming from their bank, not
 * from our app, and carries no session of ours. What authenticates the answer
 * is the state — unguessable, single use, and tied to a connection we opened
 * ourselves. The code is exchanged server-side, never in the browser.
 */
final class BankCallbackController
{
    public function __construct(
        private readonly CompleteBankAuthorization $completeBankAuthorization,
        private readonly string $adminUrl,
    ) {
    }

    #[Route('/api/finance/bank-callback', name: 'api_finance_bank_callback', methods: ['GET'])]
    public function __invoke(Request $request): RedirectResponse
    {
        $state = (string) $request->query->get('state', '');
        $code = (string) $request->query->get('code', '');
        $error = $request->query->get('error');

        if ($error !== null) {
            // The user said no, or the bank refused: that is an answer, not a bug.
            return $this->back('refused');
        }

        if ($state === '' || $code === '') {
            return $this->back('incomplete');
        }

        try {
            $result = $this->completeBankAuthorization->execute($state, $code);
        } catch (\DomainException) {
            return $this->back('unknown');
        } catch (\Throwable) {
            return $this->back('failed');
        }

        return $this->back('connected', [
            'bank' => $result['connection']->getBankName(),
            'accounts' => (string) ($result['linked'] + $result['created']),
        ]);
    }

    /** @param array<string, string> $extra */
    private function back(string $outcome, array $extra = []): RedirectResponse
    {
        return new RedirectResponse(sprintf(
            '%s#/finance/banks?%s',
            rtrim($this->adminUrl, '/'),
            http_build_query(['outcome' => $outcome] + $extra),
        ));
    }
}
