import { launchWorkerAppWindow, type AppDefinition } from "../app-definition";

const t3codeApp: AppDefinition = {
  appId: "t3code",
  title: "T3 Code",
  icon: "t3code",
  launch: (ctx) =>
    launchWorkerAppWindow(ctx, {
      createWorker: () => new Worker("./t3code-app.worker"),
      windowId: "t3code:main",
      title: "T3 Code",
      iconLetter: "T3",
      icon: "t3code",
    }),
};

export default t3codeApp;
