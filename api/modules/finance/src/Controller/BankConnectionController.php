<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Enum\BankConnectionStatus;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\ForgetBankConnection;
use Maggie\Finance\UseCase\StartBankAuthorization;
use Maggie\Finance\UseCase\SyncBankAccounts;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

final class BankConnectionController
{
    public function __construct(
        private readonly Security $security,
        private readonly EnableBankingClient $client,
        private readonly StartBankAuthorization $startBankAuthorization,
        private readonly BankConnectionRepository $connectionRepository,
        private readonly SyncBankAccounts $syncBankAccounts,
        private readonly ForgetBankConnection $forgetBankConnection,
    ) {
    }

    /** The banks the provider can reach, for the picker in the admin. */
    #[Route('/api/finance/banks', name: 'api_finance_banks', methods: ['GET'])]
    public function banks(Request $request): JsonResponse
    {
        if (!$this->security->getUser() instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $country = strtoupper((string) $request->query->get('country', 'FR'));

        if (!preg_match('/^[A-Z]{2}$/', $country)) {
            return new JsonResponse(['error' => 'country must be a two-letter code'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $banks = $this->client->listBanks($country);
        } catch (\RuntimeException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_GATEWAY);
        }

        return new JsonResponse(['banks' => array_map(static fn (array $bank) => [
            'name' => $bank['name'] ?? null,
            'country' => $bank['country'] ?? null,
            'logo' => $bank['logo'] ?? null,
        ], $banks)]);
    }

    /** The connections held for this user, and where each one stands. */
    #[Route('/api/finance/bank-connections', name: 'api_finance_bank_connections', methods: ['GET'])]
    public function connections(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse([
            'connections' => array_map(
                static fn (BankConnection $connection) => [
                    'id' => (string) $connection->getId(),
                    'bankName' => $connection->getBankName(),
                    'country' => $connection->getCountry(),
                    'status' => $connection->getStatus()->value,
                    'consentExpiresAt' => $connection->getConsentExpiresAt()?->format(\DateTimeInterface::ATOM),
                    'daysBeforeExpiry' => $connection->daysBeforeExpiry(),
                    'lastSyncedAt' => $connection->getLastSyncedAt()?->format(\DateTimeInterface::ATOM),
                    'needsReconnecting' => BankConnectionStatus::Pending !== $connection->getStatus()
                        && !$connection->isUsable(),
                ],
                $this->connectionRepository->findByUser($user),
            ),
        ]);
    }

    /**
     * Pulls the movements of every connected account.
     *
     * The request carries who is asking: a person is waiting on the answer,
     * and banks exempt those calls from the daily ceiling they apply to
     * background fetching.
     */
    #[Route('/api/finance/bank-connections/sync', name: 'api_finance_bank_sync', methods: ['POST'])]
    public function sync(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $psuHeaders = array_filter([
            'psu-ip-address' => $request->getClientIp(),
            'psu-user-agent' => $request->headers->get('User-Agent'),
        ], static fn (?string $value) => null !== $value && '' !== $value);

        try {
            $result = $this->syncBankAccounts->execute($user, false, $psuHeaders);
        } catch (\RuntimeException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_GATEWAY);
        }

        return new JsonResponse(['success' => true] + $result);
    }

    /** Opens the consent journey and hands back where to send the user. */
    #[Route('/api/finance/bank-connections/start', name: 'api_finance_bank_connect', methods: ['POST'])]
    public function start(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();
        $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);

        $bankName = $body['bankName'] ?? null;
        if (!\is_string($bankName) || '' === trim($bankName)) {
            return new JsonResponse(['error' => 'bankName is required'], Response::HTTP_BAD_REQUEST);
        }

        $country = strtoupper((string) ($body['country'] ?? 'FR'));

        try {
            $started = $this->startBankAuthorization->execute($user, $bankName, $country);
        } catch (\RuntimeException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_GATEWAY);
        }

        return new JsonResponse([
            'authorizationUrl' => $started['url'],
            'connectionId' => (string) $started['connection']->getId(),
        ]);
    }

    /**
     * Sends the user back through consent for a bank already in the list —
     * an abandoned journey or an expired access, without making them find
     * their bank in the picker again.
     */
    #[Route(
        '/api/finance/bank-connections/{id}/reconnect',
        name: 'api_finance_bank_reconnect',
        methods: ['POST'],
    )]
    public function reconnect(string $id): JsonResponse
    {
        $connection = $this->ownedConnection($id);
        if (!$connection instanceof BankConnection) {
            return $connection;
        }

        try {
            $started = $this->startBankAuthorization->execute(
                $connection->getUser(),
                $connection->getBankName(),
                $connection->getCountry(),
            );
        } catch (\RuntimeException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_GATEWAY);
        }

        return new JsonResponse([
            'authorizationUrl' => $started['url'],
            'connectionId' => (string) $started['connection']->getId(),
        ]);
    }

    /** Removes a link. The accounts it brought are kept, only unhooked. */
    #[Route('/api/finance/bank-connections/{id}', name: 'api_finance_bank_forget', methods: ['DELETE'])]
    public function forget(string $id): JsonResponse
    {
        $connection = $this->ownedConnection($id);
        if (!$connection instanceof BankConnection) {
            return $connection;
        }

        $this->forgetBankConnection->execute($connection);

        return new JsonResponse(['success' => true]);
    }

    /** The connection this user may act on, or the response saying why not. */
    private function ownedConnection(string $id): BankConnection|JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        if (!Ulid::isValid($id)) {
            return new JsonResponse(['error' => 'Not found'], Response::HTTP_NOT_FOUND);
        }

        $connection = $this->connectionRepository->find(Ulid::fromString($id));

        // Someone else's connection is not theirs to know about either.
        if (null === $connection || !$connection->getUser()->getId()->equals($user->getId())) {
            return new JsonResponse(['error' => 'Not found'], Response::HTTP_NOT_FOUND);
        }

        return $connection;
    }
}
