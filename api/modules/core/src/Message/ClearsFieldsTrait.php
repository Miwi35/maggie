<?php

declare(strict_types=1);

namespace Maggie\Core\Message;

/**
 * For Update* commands whose optional fields use null for "left untouched":
 * a field listed in $clearFields is explicitly reset to null instead.
 */
trait ClearsFieldsTrait
{
    public function clears(string $field): bool
    {
        return \in_array($field, $this->clearFields, true);
    }
}
