"""Minimal Molang evaluator for the importer's generated first-person arm keyframes.

Covers what HumanoidImport.Layout.emitAnimations emits (mod/src/.../HumanoidImport.java:1266-1283): numbers, names such
as ``ctrl.tac_hold_gun`` / ``ysm.has_mainhand``, calls with string arguments (``ctrl.tac_grip('fitted')``),
``?:``, ``||``, ``&&``, ``!``, comparisons, ``+ - * /`` and parentheses. Unknown names evaluate to 0, like an unset
Molang query.
"""
import re

_TOKEN = re.compile(r"\s*(?:(\d+\.\d*|\.\d+|\d+)|('[^']*')|([A-Za-z_][\w.]*)|(\|\||&&|==|!=|<=|>=|[-+*/()?:!<>,]))")


def _tokens(text):
    pos, out = 0, []
    text = text.strip()
    while pos < len(text):
        m = _TOKEN.match(text, pos)
        if not m:
            raise ValueError(f"molang: cannot parse {text[pos:]!r} in {text!r}")
        num, string, name, op = m.groups()
        out.append(("n", float(num)) if num else ("s", string[1:-1]) if string else ("id", name) if name else ("op", op))
        pos = m.end()
    out.append(("end", None))
    return out


class _Parser:
    def __init__(self, text, env):
        self.t, self.i, self.env = _tokens(text), 0, env

    def peek(self):
        return self.t[self.i]

    def take(self, op=None):
        tok = self.t[self.i]
        if op is not None and tok != ("op", op):
            raise ValueError(f"molang: expected {op!r}, got {tok}")
        self.i += 1
        return tok

    def expr(self):
        cond = self.or_()
        if self.peek() == ("op", "?"):
            self.take("?")
            a = self.expr()
            self.take(":")
            b = self.expr()
            return a if cond != 0 else b
        return cond

    def or_(self):
        v = self.and_()
        while self.peek() == ("op", "||"):
            self.take()
            w = self.and_()
            v = 1.0 if (v != 0 or w != 0) else 0.0
        return v

    def and_(self):
        v = self.cmp()
        while self.peek() == ("op", "&&"):
            self.take()
            w = self.cmp()
            v = 1.0 if (v != 0 and w != 0) else 0.0
        return v

    def cmp(self):
        v = self.add()
        ops = {"==": lambda a, b: a == b, "!=": lambda a, b: a != b, "<": lambda a, b: a < b,
               ">": lambda a, b: a > b, "<=": lambda a, b: a <= b, ">=": lambda a, b: a >= b}
        while self.peek()[0] == "op" and self.peek()[1] in ops:
            op = self.take()[1]
            v = 1.0 if ops[op](v, self.add()) else 0.0
        return v

    def add(self):
        v = self.mul()
        while self.peek() in (("op", "+"), ("op", "-")):
            op = self.take()[1]
            w = self.mul()
            v = v + w if op == "+" else v - w
        return v

    def mul(self):
        v = self.unary()
        while self.peek() in (("op", "*"), ("op", "/")):
            op = self.take()[1]
            w = self.unary()
            v = v * w if op == "*" else (v / w if w else 0.0)
        return v

    def unary(self):
        if self.peek() == ("op", "-"):
            self.take()
            return -self.unary()
        if self.peek() == ("op", "!"):
            self.take()
            return 0.0 if self.unary() != 0 else 1.0
        return self.atom()

    def atom(self):
        kind, val = self.take()
        if kind == "n":
            return val
        if kind == "s":
            return val
        if (kind, val) == ("op", "("):
            v = self.expr()
            self.take(")")
            return v
        if kind == "id":
            name = val.lower()
            if self.peek() == ("op", "("):
                self.take("(")
                args = []
                while self.peek() != ("op", ")"):
                    args.append(self.expr())
                    if self.peek() == ("op", ","):
                        self.take()
                self.take(")")
                fn = self.env.get(name)
                return float(fn(*args)) if callable(fn) else 0.0
            v = self.env.get(name, 0.0)
            return float(v(*()) if callable(v) else v)
        raise ValueError(f"molang: unexpected token {val!r}")


def evaluate(value, env):
    """A keyframe channel value (number or Molang string) under ``env`` (lower-case names -> value or callable)."""
    if isinstance(value, (int, float)):
        return float(value)
    p = _Parser(str(value), env)
    v = p.expr()
    if p.peek()[0] != "end":
        raise ValueError(f"molang: trailing input in {value!r}")
    return float(v)
