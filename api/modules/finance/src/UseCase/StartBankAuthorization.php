<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Enum\BankConnectionStatus;
use Maggie\Finance\Repository\BankConnectionRepository;

/**
 * Opens the consent journey at a bank.
 *
 * The connection is recorded before the user leaves, in pending state: the
 * bank answers on a separate request, and the state we hand it is the only
 * thing tying that answer back to this journey.
 *
 * A bank already known is reopened rather than duplicated. Consents expire and
 * journeys get abandoned, so the same bank is connected several times over its
 * life — each attempt piling up a row would leave the user reading a list of
 * ghosts, and the accounts attached to the old row would stop syncing.
 */
class StartBankAuthorization
{
    /** What we ask the bank for. Banks cap it; they answer with what they grant. */
    private const CONSENT_DAYS = 90;

    public function __construct(
        private readonly EnableBankingClient $client,
        private readonly EntityManagerInterface $em,
        private readonly BankConnectionRepository $connectionRepository,
        private readonly string $redirectUrl,
    ) {
    }

    /** @return array{connection: BankConnection, url: string} */
    public function execute(User $user, string $bankName, string $country = 'FR'): array
    {
        $existing = $this->connectionRepository->findOneByUserAndBank($user, $bankName, $country);
        $previous = null === $existing ? null : [
            $existing->getStatus(),
            $existing->getSessionId(),
            $existing->getConsentExpiresAt(),
        ];

        $connection = $existing ?? new BankConnection();
        $connection->setUser($user);
        $connection->setBankName($bankName);
        $connection->setCountry($country);
        $connection->reopen();

        $this->em->persist($connection);
        $this->em->flush();

        $validUntil = (new \DateTimeImmutable())->modify(sprintf('+%d days', self::CONSENT_DAYS));

        try {
            $response = $this->client->startAuthorization([
                'access' => ['valid_until' => $validUntil->format(\DateTimeInterface::ATOM)],
                'aspsp' => ['name' => $bankName, 'country' => strtoupper($country)],
                'state' => $connection->getState(),
                'redirect_url' => $this->redirectUrl,
                'psu_type' => 'personal',
            ]);
            $url = $response['url'] ?? null;
        } catch (\Throwable $e) {
            $this->abandon($connection, $previous);

            throw $e;
        }

        if (!\is_string($url) || '' === $url) {
            // Nothing to send the user to: keep no half-open journey behind.
            $this->abandon($connection, $previous);

            throw new \RuntimeException('The provider did not return an authorization URL.');
        }

        return ['connection' => $connection, 'url' => $url];
    }

    /**
     * A journey that never opened leaves nothing behind: a brand new link is
     * dropped, and a reopened one goes back to the consent it still had — a
     * failed attempt must not cost the user a working connection.
     *
     * @param array{0: BankConnectionStatus, 1: ?string, 2: ?\DateTimeImmutable}|null $previous
     */
    private function abandon(BankConnection $connection, ?array $previous): void
    {
        if (null === $previous) {
            $this->em->remove($connection);
            $this->em->flush();

            return;
        }

        $connection->setStatus($previous[0]);
        $connection->setSessionId($previous[1]);
        $connection->setConsentExpiresAt($previous[2]);
        $this->em->flush();
    }
}
