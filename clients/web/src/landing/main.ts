import { mount } from 'svelte';
import Landing from './Landing.svelte';
import '../app.css';

mount(Landing, { target: document.getElementById('app')! });
