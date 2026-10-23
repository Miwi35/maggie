<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\Message\ChangeScenarioCommand;
use Maggie\Finance\UseCase\GetScenarios;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The user's scenarios (MAG-550): read them, write them, make one current,
 * compare several. Facts only — the comparison never names a better one.
 *
 * A body carries only: name, projectIds (the projects kept, in order; absent on
 * create = every project in the user's order), repayments
 * ([{loanId, month YYYY-MM, amountCents|null}]), placementIds, monthlySaving
 * (integer cents or null). `id` is a stored scenario's, or `auto-rapid`,
 * `auto-balanced`, `auto-quality` for an automatic one not made current yet.
 */
final class ScenarioController
{
    private const FIELDS = ['name', 'projectIds', 'repayments', 'placementIds', 'monthlySaving'];
    private const ID = '[0-9A-Za-z-]+';

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
        private readonly GetScenarios $getScenarios,
    ) {
    }

    #[Route('/api/finance/scenarios', name: 'api_finance_scenarios_list', methods: ['GET'])]
    public function list(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        return new JsonResponse($this->getScenarios->list($user));
    }

    #[Route('/api/finance/scenarios/{id}', name: 'api_finance_scenarios_get', requirements: ['id' => self::ID], methods: ['GET'])]
    public function get(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $scenario = $this->getScenarios->one($user, $id);

        return null === $scenario
            ? new JsonResponse(['error' => "Scenario not found: {$id}"], Response::HTTP_NOT_FOUND)
            : new JsonResponse($scenario);
    }

    #[Route('/api/finance/scenarios', name: 'api_finance_scenarios_create', methods: ['POST'])]
    public function create(Request $request): JsonResponse
    {
        return $this->write($request, ChangeScenarioCommand::CREATE, null, Response::HTTP_CREATED, true);
    }

    #[Route('/api/finance/scenarios/{id}', name: 'api_finance_scenarios_update', requirements: ['id' => self::ID], methods: ['PATCH'])]
    public function update(Request $request, string $id): JsonResponse
    {
        return $this->write($request, ChangeScenarioCommand::UPDATE, $id, Response::HTTP_OK, true);
    }

    #[Route('/api/finance/scenarios/{id}/duplicate', name: 'api_finance_scenarios_duplicate', requirements: ['id' => self::ID], methods: ['POST'])]
    public function duplicate(Request $request, string $id): JsonResponse
    {
        return $this->write($request, ChangeScenarioCommand::DUPLICATE, $id, Response::HTTP_CREATED, true, ['name']);
    }

    #[Route('/api/finance/scenarios/{id}/make-current', name: 'api_finance_scenarios_make_current', requirements: ['id' => self::ID], methods: ['POST'])]
    public function makeCurrent(string $id): JsonResponse
    {
        return $this->write(null, ChangeScenarioCommand::MAKE_CURRENT, $id, Response::HTTP_OK, true);
    }

    #[Route('/api/finance/scenarios/{id}', name: 'api_finance_scenarios_delete', requirements: ['id' => self::ID], methods: ['DELETE'])]
    public function delete(string $id): JsonResponse
    {
        return $this->write(null, ChangeScenarioCommand::DELETE, $id, Response::HTTP_OK, false);
    }

    #[Route('/api/finance/current-scenario', name: 'api_finance_scenarios_clear_current', methods: ['DELETE'])]
    public function clearCurrent(): JsonResponse
    {
        return $this->write(null, ChangeScenarioCommand::CLEAR_CURRENT, null, Response::HTTP_OK, false);
    }

    #[Route('/api/finance/scenario-comparison', name: 'api_finance_scenarios_compare', methods: ['GET'])]
    public function compare(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $ids = array_values(array_filter(explode(',', (string) $request->query->get('ids', '')), static fn (string $id): bool => '' !== $id));

        try {
            return new JsonResponse($this->getScenarios->compare($user, $ids));
        } catch (ScenarioNotFound $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_NOT_FOUND);
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }

    /** @param list<string> $allowed the body fields this write takes */
    private function write(?Request $request, string $action, ?string $id, int $status, bool $answerScenario, array $allowed = self::FIELDS): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $fields = [];
        if (null !== $request) {
            $content = $request->getContent();
            try {
                $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
            } catch (\JsonException) {
                return new JsonResponse(['error' => 'The body is not JSON.'], Response::HTTP_BAD_REQUEST);
            }
            if (!\is_array($body) || ([] !== $body && array_is_list($body))) {
                return new JsonResponse(['error' => 'The body must be a JSON object.'], Response::HTTP_BAD_REQUEST);
            }

            $unknown = array_values(array_diff(array_keys($body), $allowed));
            if ([] !== $unknown) {
                return new JsonResponse(['error' => 'Unknown field: '.implode(', ', $unknown).'. This request takes: '.implode(', ', $allowed).'.'], Response::HTTP_BAD_REQUEST);
            }
            $fields = $body;
        }

        try {
            $scenarioId = $this->bus->dispatch(new ChangeScenarioCommand(
                userId: (string) $user->getId(),
                action: $action,
                scenarioId: $id,
                fields: $fields,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof ScenarioNotFound) {
                return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_NOT_FOUND);
            }
            if ($cause instanceof \DomainException) {
                return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
            }

            throw $e;
        }

        $answer = ['success' => true];
        if ($answerScenario && \is_string($scenarioId)) {
            $answer['scenario'] = $this->getScenarios->one($user, $scenarioId);
        }

        return new JsonResponse($answer, $status);
    }

    private static function unauthorized(): JsonResponse
    {
        return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
    }
}
