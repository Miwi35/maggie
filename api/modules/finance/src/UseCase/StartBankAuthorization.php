<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\BankConnection;

/**
 * Opens the consent journey at a bank.
 *
 * The connection is recorded before the user leaves, in pending state: the
 * bank answers on a separate request, and the state we hand it is the only
 * thing tying that answer back to this journey.
 */
class StartBankAuthorization
{
    /** What we ask the bank for. Banks cap it; they answer with what they grant. */
    private const CONSENT_DAYS = 90;

    public function __construct(
        private readonly EnableBankingClient $client,
        private readonly EntityManagerInterface $em,
        private readonly string $redirectUrl,
    ) {
    }

    /** @return array{connection: BankConnection, url: string} */
    public function execute(User $user, string $bankName, string $country = 'FR'): array
    {
        $connection = new BankConnection();
        $connection->setUser($user);
        $connection->setBankName($bankName);
        $connection->setCountry($country);

        $this->em->persist($connection);
        $this->em->flush();

        $validUntil = (new \DateTimeImmutable())->modify(sprintf('+%d days', self::CONSENT_DAYS));

        $response = $this->client->startAuthorization([
            'access' => ['valid_until' => $validUntil->format(\DateTimeInterface::ATOM)],
            'aspsp' => ['name' => $bankName, 'country' => strtoupper($country)],
            'state' => $connection->getState(),
            'redirect_url' => $this->redirectUrl,
            'psu_type' => 'personal',
        ]);

        $url = $response['url'] ?? null;

        if (!\is_string($url) || $url === '') {
            // Nothing to send the user to: keep no half-open journey behind.
            $this->em->remove($connection);
            $this->em->flush();

            throw new \RuntimeException('The provider did not return an authorization URL.');
        }

        return ['connection' => $connection, 'url' => $url];
    }
}
