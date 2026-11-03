// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
import {handle} from './_handler.mjs';
export function onRequest({request,env}){return handle(request,env);}
