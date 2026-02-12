<?php

namespace Maggie\Calendar\Middleware;

use Maggie\Calendar\Contract\MercurePublishable;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class MercurePublishMiddleware implements MiddlewareInterface
{
    public function __construct(
        private readonly HubInterface $hub,
    ) {
    }

    public function handle(Envelope $envelope, StackInterface $stack): Envelope
    {
        $envelope = $stack->next()->handle($envelope, $stack);

        $message = $envelope->getMessage();
        $parsed = self::parseCommandClass($message::class);

        if ($parsed === null) {
            return $envelope;
        }

        [$action, $topic] = $parsed;

        if ($action === 'delete') {
            $idProp = lcfirst($parsed[2]) . 'Id';
            $this->publishDelete($topic, $message->$idProp);
        } else {
            $entity = $envelope->last(HandledStamp::class)?->getResult();

            if ($entity instanceof MercurePublishable) {
                $iri = $topic . '/' . $entity->getId();
                $this->hub->publish(new Update(
                    topics: [$iri],
                    data: json_encode(['@id' => $iri] + $entity->toMercurePayload(), JSON_THROW_ON_ERROR),
                ));
            }
        }

        return $envelope;
    }

    /**
     * Parses "{Action}{Entity}Command" → [action, topic, entity].
     *
     * @return array{string, string, string}|null
     */
    private static function parseCommandClass(string $fqcn): ?array
    {
        $short = substr($fqcn, strrpos($fqcn, '\\') + 1);

        if (!str_ends_with($short, 'Command')) {
            return null;
        }

        $name = substr($short, 0, -7); // Strip "Command"

        foreach (['Create', 'Update', 'Delete'] as $prefix) {
            if (str_starts_with($name, $prefix)) {
                $entity = substr($name, strlen($prefix));
                $topic = '/api/' . strtolower($entity) . 's';

                return [strtolower($prefix), $topic, $entity];
            }
        }

        return null;
    }

    private function publishDelete(string $topic, string $id): void
    {
        $iri = $topic . '/' . $id;
        $this->hub->publish(new Update(
            topics: [$iri],
            data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
        ));
    }
}
